package com.csro.backend.offers;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ERD-mapped command core. This class performs no HTTP calls or reservation execution. */
@Service
public class OfferSelectionService {
    private static final String PROTOCOL = "offer-selection-v1";
    private static final String SEARCH_PROTOCOL = "quote-search-v1";
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final JdbcClient jdbc;
    private final TransactionTemplate write;
    private final Clock clock;

    @Autowired
    public OfferSelectionService(JdbcClient jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Clock.systemUTC());
    }

    public OfferSelectionService(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
        write = new TransactionTemplate(Objects.requireNonNull(manager));
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** effectiveAt/syntheticDemo come from the guarded server clock, never the public body. */
    public SendResult send(Principal principal, String caseId, List<String> quoteIds, String key,
                           OffsetDateTime effectiveAt, boolean syntheticDemo) {
        caseId(caseId);
        if (quoteIds == null || quoteIds.isEmpty() || quoteIds.size() > 3 || quoteIds.stream().anyMatch(id -> !identifier(id, 80))
                || new HashSet<>(quoteIds).size() != quoteIds.size() || !key(key) || effectiveAt == null)
            throw bad("INVALID_OFFER_REQUEST");
        List<String> ordered = List.copyOf(quoteIds);
        String hash = digest(JSON.writeValueAsString(Map.of("quoteIds", ordered)));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = authorize(principal, "COUNSELOR");
            Context context = lockContext(caseId, actor);
            Optional<SendReplay> previous = jdbc.sql("""
                    SELECT o.offer_code, o.request_hash, o.sent_at, m.message_code
                    FROM offers o JOIN chat_messages m ON m.offer_id=o.id AND m.case_id=o.case_id
                        AND m.message_type='OFFER'
                    WHERE o.case_id=:caseKey AND o.idempotency_key=:key
                    """).param("caseKey", context.caseKey()).param("key", key)
                    .query((rs, index) -> new SendReplay(rs.getString("request_hash"),
                            new SendResult(caseId, rs.getString("offer_code"), rs.getString("message_code"),
                                    rs.getObject("sent_at", OffsetDateTime.class)))).optional();
            if (previous.isPresent()) {
                if (!previous.orElseThrow().hash().equals(hash)) throw conflict("IDEMPOTENCY_KEY_REUSED");
                return previous.orElseThrow().result();
            }
            open(context);
            protectReplacement(context.caseKey());
            Search job = latestCompleted(context);
            List<Quote> quotes = quotes(context.caseKey(), ordered);
            requireQuotes(quotes.size() == ordered.size() && quotes.stream().map(Quote::hotelKey).distinct().count() == ordered.size());
            Group group = validateContext(context, job, quotes, effectiveAt, syntheticDemo, true);
            requireFreshAfterFailure(context.caseKey(), job);

            OffsetDateTime sentAt = now();
            supersedeSelection(context.caseKey(), sentAt);
            jdbc.sql("UPDATE offers SET status='SUPERSEDED' WHERE case_id=:caseKey AND status='ACTIVE'")
                    .param("caseKey", context.caseKey()).update();
            String offerId = "OFFER-" + UUID.randomUUID();
            String messageId = "MSG-" + UUID.randomUUID();
            Long ranking = ranking(context.caseKey(), job.id(), group.value());
            long offerKey = jdbc.sql("""
                    INSERT INTO offers (offer_code,case_id,status,sent_by,sent_at,idempotency_key,request_hash,ranking_id)
                    VALUES (:code,:caseKey,'ACTIVE',:actor,:now,:key,:hash,:ranking) RETURNING id
                    """).param("code", offerId).param("caseKey", context.caseKey()).param("actor", actor.id())
                    .param("now", sentAt).param("key", key).param("hash", hash).param("ranking", ranking)
                    .query(Long.class).single();
            for (int index = 0; index < ordered.size(); index++) {
                String code = ordered.get(index);
                long quoteKey = quotes.stream().filter(q -> q.code().equals(code)).findFirst().orElseThrow().id();
                jdbc.sql("INSERT INTO offer_items (offer_id,quote_id,display_order,case_id) VALUES (:offer,:quote,:position,:caseKey)")
                        .param("offer", offerKey).param("quote", quoteKey).param("position", index + 1)
                        .param("caseKey", context.caseKey()).update();
            }
            jdbc.sql("""
                    INSERT INTO chat_messages (message_code,case_id,sender_user_id,sender_type,message_type,body,offer_id,sent_at)
                    VALUES (:code,:caseKey,:actor,'COUNSELOR','OFFER',:body,:offer,:now)
                    """).param("code", messageId).param("caseKey", context.caseKey()).param("actor", actor.id())
                    .param("body", "대체 숙소 " + ordered.size() + "곳을 확인해 주세요. 선택은 예약 확정이 아니며 견적 차이는 실제 추가 부담금이 아닙니다.")
                    .param("offer", offerKey).param("now", sentAt).update();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("protocol", PROTOCOL); details.put("offerId", offerId); details.put("messageId", messageId);
            details.put("quoteIds", ordered); details.put("coverageMode", group.mode());
            details.put("originalAmount", group.originalAmount()); details.put("effectiveAt", effectiveAt.toString());
            details.put("clockMode", syntheticDemo ? "SYNTHETIC_DEMO" : "REAL_TIME");
            audit(context, actor, "OFFER_SENT", details, sentAt);
            return new SendResult(caseId, offerId, messageId, sentAt);
        }));
    }

    /** A new key records a new customer intent; replay never reactivates an old selection. */
    public SelectionResult select(Principal principal, String caseId, String offerId, String quoteId, String key,
                                   OffsetDateTime effectiveAt, boolean syntheticDemo) {
        caseId(caseId);
        if (!identifier(offerId, 60) || !identifier(quoteId, 80) || !key(key) || effectiveAt == null)
            throw bad("INVALID_SELECTION_REQUEST");
        // Ordered values avoid Map.of iteration order changing the hash across JVMs.
        String hash = digest(JSON.writeValueAsString(List.of(offerId, quoteId)));
        return Objects.requireNonNull(write.execute(transaction -> {
            User actor = authorize(principal, "CUSTOMER");
            Context context = lockContext(caseId, actor);
            Optional<SelectionReplay> previous = jdbc.sql("""
                    SELECT s.request_hash,s.selection_code,s.selected_at,o.offer_code,q.quote_code
                    FROM alternative_selections s JOIN offers o ON o.id=s.offer_id AND o.case_id=s.case_id
                    JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id
                    WHERE s.case_id=:caseKey AND s.idempotency_key=:key
                    """).param("caseKey", context.caseKey()).param("key", key)
                    .query((rs, index) -> new SelectionReplay(rs.getString("request_hash"),
                            new SelectionResult(caseId, rs.getString("selection_code"), rs.getString("offer_code"),
                                    rs.getString("quote_code"), rs.getObject("selected_at", OffsetDateTime.class)))).optional();
            if (previous.isPresent()) {
                if (!previous.orElseThrow().hash().equals(hash)) throw conflict("IDEMPOTENCY_KEY_REUSED");
                return previous.orElseThrow().result();
            }
            open(context);
            protectReplacement(context.caseKey());
            long offerKey = jdbc.sql("SELECT id FROM offers WHERE case_id=:caseKey AND offer_code=:code AND status='ACTIVE'")
                    .param("caseKey", context.caseKey()).param("code", offerId).query(Long.class).optional()
                    .orElseThrow(() -> conflict("OFFER_NOT_ACTIVE"));
            boolean failedOffer = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM replacement_requests r JOIN alternative_selections s
                        ON s.id=r.selection_id AND s.case_id=r.case_id
                        WHERE r.case_id=:caseKey AND r.status='FAILED' AND s.offer_id=:offer)
                    """).param("caseKey", context.caseKey()).param("offer", offerKey).query(Boolean.class).single();
            if (failedOffer) throw conflict("NEW_OFFER_REQUIRED");
            List<String> sentIds = jdbc.sql("""
                    SELECT q.quote_code FROM offer_items i JOIN alternative_quotes q ON q.id=i.quote_id AND q.case_id=i.case_id
                    WHERE i.offer_id=:offer AND i.case_id=:caseKey ORDER BY i.display_order
                    """).param("offer", offerKey).param("caseKey", context.caseKey()).query(String.class).list();
            if (!sentIds.contains(quoteId)) throw conflict("QUOTE_NOT_OFFERED");
            List<Quote> quotes = quotes(context.caseKey(), sentIds);
            requireQuotes(!sentIds.isEmpty() && sentIds.size() <= 3 && quotes.size() == sentIds.size()
                    && quotes.stream().map(Quote::hotelKey).distinct().count() == sentIds.size());
            Search job = completed(context, quotes.getFirst().jobId());
            validateContext(context, job, quotes, effectiveAt, syntheticDemo, false);
            requireFreshAfterFailure(context.caseKey(), job);
            Quote chosen = quotes.stream().filter(q -> q.code().equals(quoteId)).findFirst().orElseThrow();
            checkExpiry(chosen, effectiveAt);
            OffsetDateTime selectedAt = now();
            supersedeSelection(context.caseKey(), selectedAt);
            String selectionId = "SEL-" + UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO alternative_selections (selection_code,case_id,offer_id,quote_id,selected_by,selected_at,
                        idempotency_key,request_hash)
                    VALUES (:code,:caseKey,:offer,:quote,:actor,:now,:key,:hash)
                    """).param("code", selectionId).param("caseKey", context.caseKey()).param("offer", offerKey)
                    .param("quote", chosen.id()).param("actor", actor.id()).param("now", selectedAt)
                    .param("key", key).param("hash", hash).update();
            jdbc.sql("""
                    INSERT INTO chat_messages (message_code,case_id,sender_type,message_type,body,sent_at)
                    VALUES (:code,:caseKey,'SYSTEM','SYSTEM',:body,:now)
                    """).param("code", "MSG-" + UUID.randomUUID()).param("caseKey", context.caseKey())
                    .param("body", "고객이 " + chosen.propertyName() + "을(를) 선택했습니다. 예약 진행 전이며 아직 예약이 확정되지 않았습니다.")
                    .param("now", selectedAt).update();
            audit(context, actor, "ALTERNATIVE_SELECTED", Map.of("protocol", PROTOCOL, "selectionId", selectionId,
                    "offerId", offerId, "quoteId", quoteId), selectedAt);
            return new SelectionResult(caseId, selectionId, offerId, quoteId, selectedAt);
        }));
    }

    private User authorize(Principal principal, String role) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED");
        User user = jdbc.sql("SELECT id,role FROM users WHERE subject_key=:subject AND is_active")
                .param("subject", principal.getName()).query((rs, index) -> new User(rs.getLong("id"), rs.getString("role")))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"));
        if (!role.equals(user.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        return user;
    }

    private Context lockContext(String code, User actor) {
        long booking = jdbc.sql("""
                SELECT b.id FROM cases c JOIN bookings b ON b.id=c.booking_id
                WHERE c.case_code=:code AND (:counselor OR b.customer_id=:actor)
                """).param("code", code).param("counselor", "COUNSELOR".equals(actor.role()))
                .param("actor", actor.id()).query(Long.class).optional().orElseThrow(OfferSelectionService::notFound);
        Booking b = jdbc.sql("""
                SELECT b.*,h.hotel_code FROM bookings b JOIN hotels h ON h.id=b.hotel_id WHERE b.id=:booking FOR UPDATE OF b
                """).param("booking", booking).query((rs, index) -> new Booking(booking, rs.getLong("customer_id"),
                        rs.getLong("hotel_id"), rs.getString("hotel_code"), rs.getString("supplier_code"), rs.getString("currency"),
                        new Stay(rs.getObject("check_in_date", LocalDate.class), rs.getObject("check_out_date", LocalDate.class),
                                rs.getInt("adults"), rs.getInt("rooms")))).single();
        if ("CUSTOMER".equals(actor.role()) && b.customerId() != actor.id()) throw notFound();
        return jdbc.sql("SELECT id,status FROM cases WHERE case_code=:code AND booking_id=:booking FOR UPDATE")
                .param("code", code).param("booking", booking).query((rs, index) ->
                        new Context(rs.getLong("id"), code, rs.getString("status"), b)).optional()
                .orElseThrow(OfferSelectionService::notFound);
    }

    private Search latestCompleted(Context context) {
        return jdbc.sql("""
                SELECT id,parameters::text,result_payload::text,created_at FROM jobs
                WHERE case_id=:caseKey AND booking_id=:booking AND kind='QUOTE_SEARCH' AND status='COMPLETED'
                    AND parameters->>'protocol'=:protocol ORDER BY id DESC LIMIT 1
                """).param("caseKey", context.caseKey()).param("booking", context.booking().id()).param("protocol", SEARCH_PROTOCOL)
                .query(this::search).optional().orElseThrow(() -> conflict("QUOTE_INVALID"));
    }

    private Search completed(Context context, long job) {
        return jdbc.sql("""
                SELECT id,parameters::text,result_payload::text,created_at FROM jobs
                WHERE id=:job AND case_id=:caseKey AND booking_id=:booking AND kind='QUOTE_SEARCH' AND status='COMPLETED'
                    AND parameters->>'protocol'=:protocol
                """).param("job", job).param("caseKey", context.caseKey()).param("booking", context.booking().id())
                .param("protocol", SEARCH_PROTOCOL).query(this::search).optional().orElseThrow(() -> conflict("QUOTE_INVALID"));
    }

    private Search search(ResultSet rs, int index) throws SQLException {
        return new Search(rs.getLong("id"), JSON.readTree(rs.getString("parameters")),
                JSON.readTree(rs.getString("result_payload")), rs.getObject("created_at", OffsetDateTime.class));
    }

    private List<Quote> quotes(long caseKey, List<String> codes) {
        return jdbc.sql("""
                SELECT q.*,h.hotel_code FROM alternative_quotes q JOIN hotels h ON h.id=q.hotel_id
                WHERE q.case_id=:caseKey AND q.quote_code IN (:codes) ORDER BY q.id
                """).param("caseKey", caseKey).param("codes", codes).query((rs, index) -> new Quote(rs.getLong("id"),
                        rs.getString("quote_code"), rs.getLong("hotel_id"), rs.getString("hotel_code"), rs.getLong("search_job_id"),
                        new Stay(rs.getObject("check_in_date", LocalDate.class), rs.getObject("check_out_date", LocalDate.class),
                                rs.getInt("adults"), rs.getInt("rooms")),
                        rs.getString("source_code"), rs.getString("property_name"), rs.getString("room_type_code"), rs.getString("room_name"),
                        rs.getString("rate_plan_code"), rs.getInt("distance_meters"), rs.getInt("available_room_count"), rs.getInt("max_adults"),
                        rs.getLong("base_amount"), rs.getLong("mandatory_fees_amount"), rs.getLong("total_amount"), rs.getString("currency"),
                        rs.getBoolean("breakfast_included"), rs.getString("cancellation_policy"),
                        rs.getObject("checked_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                        JSON.readTree(rs.getString("source_payload")))).list();
    }

    private Group validateContext(Context context, Search job, List<Quote> quotes, OffsetDateTime effectiveAt,
                                  boolean syntheticDemo, boolean checkAllExpiry) {
        try {
            JsonNode scope = job.parameters().path("scope");
            Booking booking = context.booking();
            requireQuotes("MOCK_SUPPLIER".equals(booking.supplier()) && "KRW".equals(booking.currency())
                    && "MOCK_SUPPLIER".equals(text(scope, "supplierCode")) && "KRW".equals(text(scope, "currency"))
                    && number(scope, "caseId") == context.caseKey() && number(scope, "bookingId") == booking.id()
                    && number(scope, "hotelId") == booking.hotelKey() && text(scope, "hotelCode").equals(booking.hotelCode())
                    && stay(scope.path("stay")).equals(booking.stay())
                    && job.parameters().path("syntheticDemo").isBoolean()
                    && job.parameters().path("syntheticDemo").booleanValue() == syntheticDemo
                    && "COMPLETED".equals(text(job.result(), "stage")));
            Stay period = quotes.getFirst().stay();
            requireQuotes(quotes.stream().allMatch(q -> q.jobId() == job.id() && q.stay().equals(period)));
            JsonNode groups = job.result().path("groups");
            requireQuotes(groups.isArray());
            List<JsonNode> matching = new ArrayList<>();
            for (JsonNode group : groups) if (stay(group.path("search").path("stay")).equals(period)) matching.add(group);
            requireQuotes(matching.size() == 1);
            JsonNode group = matching.getFirst();
            String mode = text(group, "mode");
            requireQuotes(period.adults() == booking.stay().adults() && period.rooms() == booking.stay().rooms());
            validateCoverage(context, period, mode);
            JsonNode search = group.path("search");
            JsonNode raw = JSON.readTree(text(search, "responseJson"));
            JsonNode request = JSON.readTree(text(search, "requestJson"));
            requireQuotes("COMPLETED".equals(text(raw, "searchStatus")) && "KRW".equals(text(raw, "currency"))
                    && "KRW".equals(text(search, "currency")) && stay(raw.path("stay")).equals(period)
                    && stay(request.path("stay")).equals(period)
                    && text(request, "originPropertyId").equals(booking.hotelCode())
                    && text(raw, "searchRequestId").equals(text(search, "searchRequestId"))
                    && text(raw, "searchRequestId").equals(text(request, "searchRequestId"))
                    && time(raw, "checkedAt").toInstant().equals(time(search, "checkedAt").toInstant()));
            JsonNode rawQuotes = raw.path("quotes"), checkpointQuotes = search.path("quotes");
            requireQuotes(rawQuotes.isArray() && checkpointQuotes.isArray() && rawQuotes.size() == checkpointQuotes.size());
            Set<String> checkpointIds = new HashSet<>();
            for (JsonNode item : checkpointQuotes) requireQuotes(checkpointIds.add(text(item, "quoteId")));
            Set<String> rawIds = new HashSet<>();
            for (JsonNode item : rawQuotes) requireQuotes(rawIds.add(text(item, "quoteId")));
            requireQuotes(rawIds.equals(checkpointIds));
            for (Quote quote : quotes) {
                JsonNode origin = null;
                for (JsonNode item : rawQuotes) if (quote.code().equals(text(item, "quoteId"))) origin = item;
                requireQuotes(origin != null && origin.equals(quote.raw()));
                validateFacts(quote);
                requireQuotes(quote.checkedAt().toInstant().equals(time(raw, "checkedAt").toInstant()));
                if (checkAllExpiry) checkExpiry(quote, effectiveAt);
                else requireQuotes(!quote.checkedAt().isAfter(effectiveAt));
            }
            Long original = "FULL_STAY".equals(mode) ? number(scope, "originalAmount") : null;
            requireQuotes(original == null || original >= 0);
            return new Group(mode, original, group);
        } catch (ResponseStatusException expected) {
            throw expected;
        } catch (RuntimeException malformed) {
            throw conflict("QUOTE_INVALID");
        }
    }

    private void validateCoverage(Context context, Stay period, String mode) {
        Stay full = context.booking().stay();
        List<Night> nights = jdbc.sql("SELECT stay_date,fulfillment_status,fact_status FROM booking_nights WHERE booking_id=:booking ORDER BY stay_date")
                .param("booking", context.booking().id()).query((rs, index) -> new Night(rs.getObject("stay_date", LocalDate.class),
                        rs.getString("fulfillment_status"), rs.getString("fact_status"))).list();
        requireQuotes(full.checkInDate().datesUntil(full.checkOutDate()).toList().equals(nights.stream().map(Night::date).toList()));
        if ("FULL_STAY".equals(mode)) {
            requireQuotes(period.equals(full));
            return;
        }
        requireQuotes("VERIFIED_FAILURE_ONLY".equals(mode));
        List<LocalDate> failed = nights.stream().filter(n -> "VERIFIED".equals(n.fact()) && "UNAVAILABLE".equals(n.fulfillment()))
                .map(Night::date).toList();
        boolean found = false;
        for (int index = 0; index < failed.size();) {
            LocalDate first = failed.get(index++), last = first;
            while (index < failed.size() && failed.get(index).equals(last.plusDays(1))) last = failed.get(index++);
            if (first.equals(period.checkInDate()) && last.plusDays(1).equals(period.checkOutDate())) found = true;
        }
        // Completed worker provenance records the full-first decision. Its empty
        // full-stay response is not retained by the existing search implementation.
        requireQuotes(found);
    }

    private static void validateFacts(Quote q) {
        JsonNode r = q.raw(), p = r.path("price");
        requireQuotes("SIMULATED_SUPPLIER".equals(q.source()) && "KRW".equals(q.currency())
                && q.source().equals(text(r, "source")) && q.code().equals(text(r, "quoteId"))
                && q.hotelCode().equals(text(r, "propertyId")) && q.propertyName().equals(text(r, "propertyName"))
                && q.roomType().equals(text(r, "roomTypeId")) && q.roomName().equals(text(r, "roomName"))
                && q.ratePlan().equals(text(r, "ratePlanId")) && q.distance() == number(r, "distanceMeters")
                && q.availableRooms() == number(r, "availableRoomCount") && q.maxAdults() == number(r, "maxAdults")
                && q.base() == number(p, "baseAmount") && q.fees() == number(p, "mandatoryFeesAmount")
                && q.total() == number(p, "totalAmount") && q.currency().equals(text(p, "currency"))
                && q.base() >= 0 && q.fees() >= 0 && q.total() >= 0
                && BigInteger.valueOf(q.base()).add(BigInteger.valueOf(q.fees())).equals(BigInteger.valueOf(q.total()))
                && q.distance() >= 0 && q.availableRooms() >= q.stay().rooms()
                && q.maxAdults() > 0 && (long) q.maxAdults() * q.stay().rooms() >= q.stay().adults()
                && r.path("breakfastIncluded").isBoolean() && q.breakfast() == r.path("breakfastIncluded").booleanValue()
                && q.cancellation().equals(text(r, "cancellationPolicy"))
                && Set.of("NON_REFUNDABLE", "FREE_CANCELLATION", "PARTIAL_REFUND").contains(q.cancellation())
                && q.checkedAt().toInstant().equals(time(r, "checkedAt").toInstant())
                && q.expiresAt().toInstant().equals(time(r, "expiresAt").toInstant())
                && q.expiresAt().isAfter(q.checkedAt()));
    }

    private Long ranking(long caseKey, long job, JsonNode group) {
        JsonNode ranking = group.path("ranking");
        if (!ranking.isObject()) return null;
        return jdbc.sql("""
                SELECT id FROM quote_rankings WHERE case_id=:caseKey AND search_job_id=:job AND validation_status='VALID'
                    AND input_payload=CAST(:input AS jsonb) AND result_payload=CAST(:result AS jsonb) ORDER BY id DESC LIMIT 1
                """).param("caseKey", caseKey).param("job", job).param("input", text(ranking, "requestJson"))
                .param("result", text(ranking, "responseJson")).query(Long.class).optional().orElse(null);
    }

    private void protectReplacement(long caseKey) {
        boolean locked = jdbc.sql("SELECT EXISTS (SELECT 1 FROM replacement_requests WHERE case_id=:caseKey AND status IN ('PROCESSING','RESULT_UNKNOWN','CONFIRMED'))")
                .param("caseKey", caseKey).query(Boolean.class).single();
        if (locked) throw conflict("REPLACEMENT_LOCKED");
    }

    private void requireFreshAfterFailure(long caseKey, Search job) {
        boolean stale = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id AND s.case_id=r.case_id
                    JOIN alternative_quotes q ON q.id=s.quote_id AND q.case_id=s.case_id
                    WHERE r.case_id=:caseKey AND r.status='FAILED' AND (q.search_job_id>=:job OR r.updated_at>=:created))
                """).param("caseKey", caseKey).param("job", job.id()).param("created", job.createdAt()).query(Boolean.class).single();
        if (stale) throw conflict("QUOTE_REFRESH_REQUIRED");
    }

    private void supersedeSelection(long caseKey, OffsetDateTime at) {
        jdbc.sql("UPDATE alternative_selections SET superseded_at=GREATEST(selected_at,:now) WHERE case_id=:caseKey AND superseded_at IS NULL")
                .param("now", at).param("caseKey", caseKey).update();
    }

    private void audit(Context context, User actor, String type, Map<String, Object> details, OffsetDateTime at) {
        jdbc.sql("""
                INSERT INTO case_actions (case_id,actor_id,action_type,details,acted_at,booking_id)
                VALUES (:caseKey,:actor,:type,CAST(:details AS jsonb),:now,:booking)
                """).param("caseKey", context.caseKey()).param("actor", actor.id()).param("type", type)
                .param("details", JSON.writeValueAsString(details)).param("now", at).param("booking", context.booking().id()).update();
    }

    private static void open(Context context) { if ("CLOSED".equals(context.status())) throw conflict("CASE_CLOSED"); }
    private static void checkExpiry(Quote quote, OffsetDateTime effectiveAt) {
        requireQuotes(!quote.checkedAt().isAfter(effectiveAt));
        if (!quote.expiresAt().isAfter(effectiveAt)) throw conflict("QUOTE_EXPIRED");
    }
    // PostgreSQL TIMESTAMPTZ stores microseconds; first and replay DTOs must agree.
    private OffsetDateTime now() { return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC); }
    private static Stay stay(JsonNode value) {
        return new Stay(LocalDate.parse(text(value, "checkInDate")), LocalDate.parse(text(value, "checkOutDate")),
                Math.toIntExact(number(value, "adults")), Math.toIntExact(number(value, "rooms")));
    }
    private static String text(JsonNode value, String field) {
        JsonNode item = value.path(field);
        requireQuotes(item.isString() && !item.stringValue().isBlank());
        return item.stringValue();
    }
    private static long number(JsonNode value, String field) {
        JsonNode item = value.path(field);
        requireQuotes(item.isIntegralNumber() && item.canConvertToLong());
        return item.longValue();
    }
    private static OffsetDateTime time(JsonNode value, String field) { return OffsetDateTime.parse(text(value, field)); }
    private static void requireQuotes(boolean valid) { if (!valid) throw conflict("QUOTE_INVALID"); }
    private static boolean identifier(String id, int limit) {
        return id != null && !id.isBlank() && id.length() <= limit && id.chars().noneMatch(Character::isISOControl);
    }
    private static boolean key(String key) { return key != null && key.matches("[A-Za-z0-9._:-]{1,100}"); }
    private static void caseId(String caseId) { if (caseId == null || !caseId.matches("C-[0-9]{3,38}")) throw bad("INVALID_CASE_ID"); }
    private static ResponseStatusException bad(String code) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, code); }
    private static ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"); }
    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    public record SendResult(String caseId, String offerId, String messageId, OffsetDateTime sentAt) {}
    public record SelectionResult(String caseId, String selectionId, String offerId, String quoteId, OffsetDateTime selectedAt) {}
    private record User(long id, String role) {}
    private record Stay(LocalDate checkInDate, LocalDate checkOutDate, int adults, int rooms) {}
    private record Booking(long id, long customerId, long hotelKey, String hotelCode, String supplier, String currency, Stay stay) {}
    private record Context(long caseKey, String caseCode, String status, Booking booking) {}
    private record Search(long id, JsonNode parameters, JsonNode result, OffsetDateTime createdAt) {}
    private record Group(String mode, Long originalAmount, JsonNode value) {}
    private record Night(LocalDate date, String fulfillment, String fact) {}
    private record Quote(long id, String code, long hotelKey, String hotelCode, long jobId, Stay stay,
                         String source, String propertyName, String roomType, String roomName, String ratePlan,
                         int distance, int availableRooms, int maxAdults, long base, long fees, long total, String currency,
                         boolean breakfast, String cancellation, OffsetDateTime checkedAt, OffsetDateTime expiresAt, JsonNode raw) {}
    private record SendReplay(String hash, SendResult result) {}
    private record SelectionReplay(String hash, SelectionResult result) {}
}
