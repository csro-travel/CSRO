package com.csro.backend.cases;

import com.csro.backend.searching.AlternativeSearchResultService;
import java.net.URI;
import java.security.Principal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("local-chat-demo")
class CaseAirbnbGuideService {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final CaseAccess access;
    private final AlternativeSearchResultService searches;
    private final CaseMessageCommands messages;
    private final ObjectProvider<LocalAlternativeBooking> demoBooking;

    CaseAirbnbGuideService(JdbcClient jdbc, CaseAccess access,
                           AlternativeSearchResultService searches, CaseMessageCommands messages,
                           ObjectProvider<LocalAlternativeBooking> demoBooking) {
        this.jdbc = jdbc;
        this.access = access;
        this.searches = searches;
        this.messages = messages;
        this.demoBooking = demoBooking;
    }

    @Transactional
    public void saveSelection(Principal principal, String caseCode, UUID searchId, JsonNode body) {
        var sender = access.chatUser(principal, caseCode);
        if (!sender.isCounselor()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        long caseId = lockCase(caseCode);
        if ("CLOSED".equals(access.chatCase(sender, caseCode, false).status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "CASE_CLOSED");
        }
        List<String> ids = listingIds(body, 0);
        JsonNode snapshot = JSON.readTree(searches.snapshot(caseCode, null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "AIRBNB_SEARCH_UNAVAILABLE")));
        if (!searchId.toString().equals(snapshot.path("searchId").asText())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AIRBNB_SEARCH_CHANGED");
        }
        var available = new HashSet<String>();
        for (JsonNode candidate : snapshot.path("result").path("top_recommendations")) {
            available.add(candidate.path("listing_id").asText());
        }
        if (!available.containsAll(ids)) throw invalidSelection();

        String details = JSON.writeValueAsString(Map.of("searchId", searchId.toString(), "listingIds", ids));
        int changed = jdbc.sql(
                "UPDATE case_actions SET actor_id = :actorId, details = CAST(:details AS jsonb), acted_at = clock_timestamp() "
                + "WHERE case_id = :caseId AND action_type = 'AIRBNB_SELECTION_DRAFT' AND details ->> 'searchId' = :searchId")
                .param("caseId", caseId).param("actorId", sender.id()).param("details", details)
                .param("searchId", searchId.toString()).update();
        if (changed == 0) {
            jdbc.sql(
                    "INSERT INTO case_actions (case_id, booking_id, actor_id, action_type, details, acted_at) "
                    + "SELECT id, booking_id, :actorId, 'AIRBNB_SELECTION_DRAFT', CAST(:details AS jsonb), clock_timestamp() "
                    + "FROM cases WHERE id = :caseId")
                    .param("caseId", caseId).param("actorId", sender.id()).param("details", details).update();
        }
    }

    @Transactional
    public void send(Principal principal, String caseCode, UUID searchId, UUID requestId, JsonNode body) {
        var sender = access.chatUser(principal, caseCode);
        if (!sender.isCounselor()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        var simulation = demoBooking.getIfAvailable();
        if (simulation != null) simulation.lockBooking(caseCode);
        long caseId = lockCase(caseCode);
        List<String> selectedIds = listingIds(body, 3);
        JsonNode ids = body.path("listingIds");

        var previous = jdbc.sql("""
                SELECT details::text FROM case_actions
                WHERE case_id = :caseId AND action_type = 'AIRBNB_GUIDE_SENT'
                  AND details ->> 'requestId' = :requestId
                """).param("caseId", caseId).param("requestId", requestId.toString())
                .query(String.class).optional();
        if (previous.isPresent()) {
            JsonNode action = JSON.readTree(previous.get());
            if (!searchId.toString().equals(action.path("searchId").asText()) || !ids.equals(action.path("listingIds"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "AIRBNB_GUIDE_REQUEST_CONFLICT");
            }
            return;
        }

        JsonNode snapshot = JSON.readTree(searches.snapshot(caseCode, searchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "AIRBNB_SEARCH_UNAVAILABLE")));
        List<GuideItem> items = new ArrayList<>();
        for (String id : selectedIds) {
            GuideItem item = null;
            for (JsonNode candidate : snapshot.path("result").path("top_recommendations")) {
                if (id.equals(candidate.path("listing_id").asText())) {
                    item = guideItem(id, candidate);
                    break;
                }
            }
            if (item == null) throw invalidSelection();
            items.add(item);
        }

        JsonNode message = JSON.readTree(messages.send(principal, caseCode, formatMessage(items)));
        String details = JSON.writeValueAsString(Map.of(
                "requestId", requestId.toString(), "searchId", searchId.toString(),
                "listingIds", selectedIds, "messageId", message.path("messageId").asText(),
                "items", items));
        jdbc.sql("""
                INSERT INTO case_actions (case_id, booking_id, actor_id, action_type, details, acted_at)
                SELECT id, booking_id, :actorId, 'AIRBNB_GUIDE_SENT', CAST(:details AS jsonb),
                       CAST(:sentAt AS timestamptz)
                FROM cases WHERE id = :caseId
                """).param("caseId", caseId).param("actorId", sender.id()).param("details", details)
                .param("sentAt", message.path("sentAt").asText()).update();
        if (simulation != null) {
            simulation.stage(caseCode, sender.id(), searchId, requestId, selectedIds, snapshot.path("result"),
                    message.path("sentAt").asText());
        }
    }

    private long lockCase(String caseCode) {
        return jdbc.sql("SELECT id FROM cases WHERE case_code = :caseCode FOR UPDATE")
                .param("caseCode", caseCode).query(Long.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
    }

    private static List<String> listingIds(JsonNode body, int minimum) {
        JsonNode ids = body.path("listingIds");
        if (!body.isObject() || body.size() != 1 || !ids.isArray() || ids.size() < minimum || ids.size() > 3) {
            throw invalidSelection();
        }
        List<String> selectedIds = new ArrayList<>();
        for (JsonNode id : ids) {
            if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 200) throw invalidSelection();
            selectedIds.add(id.asText());
        }
        if (new HashSet<>(selectedIds).size() != selectedIds.size()) throw invalidSelection();
        return selectedIds;
    }

    private static GuideItem guideItem(String id, JsonNode candidate) {
        String name = singleLine(candidate.path("name").asText(""));
        String url = candidate.path("listing_url").asText("");
        JsonNode price = candidate.path("total_price_krw");
        if (name.isBlank() || !price.isIntegralNumber() || !price.canConvertToLong()
                || price.asLong() < 0 || !isAirbnbUrl(url)) throw invalidSelection();
        return new GuideItem(id, name, price.asLong(), url, singleLine(candidate.path("customer_highlight").asText("")));
    }

    private static String singleLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static boolean isAirbnbUrl(String value) {
        try {
            URI url = URI.create(value);
            String host = url.getHost();
            if (!"https".equalsIgnoreCase(url.getScheme()) || host == null || url.getUserInfo() != null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("airbnb.com") || host.endsWith(".airbnb.com")
                    || host.equals("airbnb.co.kr") || host.endsWith(".airbnb.co.kr");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String formatMessage(List<GuideItem> items) {
        List<String> blocks = new ArrayList<>();
        blocks.add("대체 숙소 후보를 안내드립니다. 표시된 금액은 검색 시점의 전체 숙박 요금입니다.");
        NumberFormat amount = NumberFormat.getIntegerInstance(Locale.KOREA);
        for (int index = 0; index < items.size(); index++) {
            GuideItem item = items.get(index);
            String block = (index + 1) + ". " + item.name() + " · " + amount.format(item.totalPriceKrw()) + "원\n" + item.url();
            if (!item.highlight().isBlank()) block += "\n" + item.highlight();
            blocks.add(block);
        }
        blocks.add("예약은 진행되지 않았습니다. 실제 예약 가능 여부와 최종 요금은 숙소 링크에서 확인해 주세요.");
        return String.join("\n\n", blocks);
    }

    private static ResponseStatusException invalidSelection() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_AIRBNB_GUIDE_SELECTION");
    }

    record GuideItem(String listingId, String name, long totalPriceKrw, String url, String highlight) {}
}
