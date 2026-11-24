package com.csro.backend.quotes;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Existing API-028/025 wire snapshots. Identity and monetary facts originate in the supplier response. */
public final class QuoteContracts {
    static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private QuoteContracts() {}

    public record Stay(LocalDate checkInDate, LocalDate checkOutDate, int adults, int rooms) {}
    public record SearchInput(String searchRequestId, String originPropertyId, Stay stay,
                              int radiusMeters, Long maxAverageNightlyAmount) {}
    public record Quote(String quoteId, String source, String propertyId, String propertyName,
                        int distanceMeters, String roomTypeId, String roomName, String ratePlanId,
                        int maxAdults, boolean breakfastIncluded, int availableRoomCount,
                        long baseAmount, long mandatoryFeesAmount, long totalAmount, String currency,
                        String cancellationPolicy, OffsetDateTime checkedAt, OffsetDateTime expiresAt,
                        String sourceJson) {}
    public record SearchResult(String searchRequestId, Stay stay, OffsetDateTime checkedAt, String currency,
                               List<Quote> quotes, String requestJson, String responseJson) {
        public SearchResult { quotes = List.copyOf(quotes); }
    }
    public record RankInput(String caseId, String bookingId, Stay stay, Long originalStayTotal,
                            List<Quote> quotes) {
        public RankInput { quotes = List.copyOf(quotes); }
    }
    public record Reason(String quoteId, List<String> reasonCodes, String summary) {
        public Reason { reasonCodes = List.copyOf(reasonCodes); }
    }
    public record Ranking(String caseId, String rankingVersion, List<String> rankedQuoteIds,
                          List<Reason> reasons, List<String> warnings, String requestJson, String responseJson) {
        public Ranking {
            rankedQuoteIds = List.copyOf(rankedQuoteIds);
            reasons = List.copyOf(reasons);
            warnings = List.copyOf(warnings);
        }
    }

    static Map<String, Object> searchWire(SearchInput input) {
        require(input != null);
        identifier(input.searchRequestId(), 100);
        identifier(input.originPropertyId(), 40);
        validateStay(input.stay());
        require(input.radiusMeters() >= 1 && input.radiusMeters() <= 20000);
        require(input.maxAverageNightlyAmount() == null || input.maxAverageNightlyAmount() > 0);
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("radiusMeters", input.radiusMeters());
        filters.put("currency", "KRW");
        if (input.maxAverageNightlyAmount() != null) filters.put("maxAverageNightlyAmount", input.maxAverageNightlyAmount());
        return Map.of("searchRequestId", input.searchRequestId(), "originPropertyId", input.originPropertyId(),
                "stay", stayWire(input.stay(), true), "filters", filters);
    }

    static SearchResult readSearch(byte[] bytes, SearchInput input, String requestJson) {
        Map<?, ?> body = object(JSON.readValue(bytes, Object.class));
        require("COMPLETED".equals(text(body.get("searchStatus"), 20)));
        require(input.searchRequestId().equals(text(body.get("searchRequestId"), 100)));
        Stay stay = readStay(object(body.get("stay")));
        require(input.stay().equals(stay));
        OffsetDateTime checkedAt = timestamp(body.get("checkedAt"));
        require("KRW".equals(text(body.get("currency"), 3)));
        List<?> rows = array(body.get("quotes"), 0, 100);
        List<Quote> quotes = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object row : rows) {
            Map<?, ?> item = object(row);
            if (item.containsKey("imageAssetKey")) text(item.get("imageAssetKey"), 1000);
            if (item.containsKey("amenityCodes")) {
                List<String> amenities = textArray(item.get("amenityCodes"), 0, 5, 40);
                require(Set.of("WIFI", "PARKING", "POOL", "BREAKFAST", "SHUTTLE").containsAll(amenities));
            }
            Map<?, ?> price = object(item.get("price"));
            exact(price, Set.of("baseAmount", "mandatoryFeesAmount", "totalAmount", "currency"));
            Object breakfast = item.get("breakfastIncluded");
            require(breakfast instanceof Boolean);
            Quote quote = new Quote(text(item.get("quoteId"), 80), text(item.get("source"), 40),
                    text(item.get("propertyId"), 40), text(item.get("propertyName"), 160),
                    integer(item.get("distanceMeters"), 0, Integer.MAX_VALUE),
                    text(item.get("roomTypeId"), 80), text(item.get("roomName"), 160),
                    text(item.get("ratePlanId"), 80), integer(item.get("maxAdults"), 1, Short.MAX_VALUE),
                    (Boolean) breakfast, integer(item.get("availableRoomCount"), 0, Integer.MAX_VALUE),
                    amount(price.get("baseAmount")), amount(price.get("mandatoryFeesAmount")), amount(price.get("totalAmount")),
                    text(price.get("currency"), 3), text(item.get("cancellationPolicy"), 40),
                    timestamp(item.get("checkedAt")), timestamp(item.get("expiresAt")), JSON.writeValueAsString(item));
            validateQuote(quote, stay);
            require(ids.add(quote.quoteId()) && quote.checkedAt().toInstant().equals(checkedAt.toInstant()));
            require(quote.distanceMeters() <= input.radiusMeters());
            if (input.maxAverageNightlyAmount() != null) {
                BigInteger limit = BigInteger.valueOf(input.maxAverageNightlyAmount())
                        .multiply(BigInteger.valueOf(ChronoUnit.DAYS.between(stay.checkInDate(), stay.checkOutDate())));
                require(BigInteger.valueOf(quote.totalAmount()).compareTo(limit) <= 0);
            }
            quotes.add(quote);
        }
        return new SearchResult(input.searchRequestId(), stay, checkedAt, "KRW", quotes, requestJson,
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    static Map<String, Object> rankingWire(RankInput input) {
        require(input != null);
        identifier(input.caseId(), 40);
        identifier(input.bookingId(), 40);
        validateStay(input.stay());
        require(input.originalStayTotal() == null || input.originalStayTotal() >= 0);
        require(input.quotes() != null && !input.quotes().isEmpty() && input.quotes().size() <= 20);
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> quotes = new ArrayList<>();
        for (Quote quote : input.quotes()) {
            validateQuote(quote, input.stay());
            require(ids.add(quote.quoteId()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("quoteId", quote.quoteId()); row.put("hotelName", quote.propertyName());
            row.putAll(stayWire(input.stay(), false));
            row.put("distanceMeters", quote.distanceMeters()); row.put("totalAmount", quote.totalAmount());
            row.put("currency", quote.currency()); row.put("checkedAt", quote.checkedAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            quotes.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caseId", input.caseId()); body.put("bookingId", input.bookingId());
        body.put("requestedStay", stayWire(input.stay(), false));
        if (input.originalStayTotal() != null)
            body.put("originalStayTotal", Map.of("amount", input.originalStayTotal(), "currency", "KRW"));
        body.put("quotes", quotes);
        return body;
    }

    static Ranking readRanking(byte[] bytes, RankInput input, String requestJson) {
        Map<?, ?> body = object(JSON.readValue(bytes, Object.class));
        exact(body, Set.of("caseId", "rankingVersion", "rankedQuoteIds", "reasons", "warnings"));
        String caseId = text(body.get("caseId"), 40);
        require(input.caseId().equals(caseId));
        String version = text(body.get("rankingVersion"), 80);
        List<String> rankedIds = textArray(body.get("rankedQuoteIds"), 1, 20, 80);
        Set<String> expectedIds = new HashSet<>(input.quotes().stream().map(Quote::quoteId).toList());
        int nearest = input.quotes().stream().mapToInt(Quote::distanceMeters).min().orElseThrow();
        long lowestPrice = input.quotes().stream().mapToLong(Quote::totalAmount).min().orElseThrow();
        require(rankedIds.size() == expectedIds.size() && new HashSet<>(rankedIds).equals(expectedIds));
        List<Reason> reasons = new ArrayList<>();
        Set<String> explained = new HashSet<>();
        for (Object row : array(body.get("reasons"), 1, 20)) {
            Map<?, ?> reason = object(row);
            exact(reason, Set.of("quoteId", "reasonCodes", "summary"));
            String id = text(reason.get("quoteId"), 80);
            require(expectedIds.contains(id) && explained.add(id));
            List<String> codes = textArray(reason.get("reasonCodes"), 1, 3, 40);
            require(Set.of("NEAREST", "LOWEST_PRICE", "ALTERNATIVE").containsAll(codes));
            Quote quoted = input.quotes().stream().filter(quote -> quote.quoteId().equals(id)).findFirst().orElseThrow();
            require(!codes.contains("NEAREST") || quoted.distanceMeters() == nearest);
            require(!codes.contains("LOWEST_PRICE") || quoted.totalAmount() == lowestPrice);
            reasons.add(new Reason(id, codes, text(reason.get("summary"), 4000)));
        }
        require(explained.equals(expectedIds));
        List<String> warnings = textArray(body.get("warnings"), 0, 100, 4000, false);
        return new Ranking(caseId, version, rankedIds, reasons, warnings, requestJson,
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    static void validateQuote(Quote quote, Stay stay) {
        require(quote != null);
        identifier(quote.quoteId(), 80); identifier(quote.propertyId(), 40);
        identifier(quote.roomTypeId(), 80); identifier(quote.ratePlanId(), 80);
        text(quote.propertyName(), 160); text(quote.roomName(), 160);
        require("SIMULATED_SUPPLIER".equals(quote.source()) && "KRW".equals(quote.currency()));
        require(quote.baseAmount() >= 0 && quote.mandatoryFeesAmount() >= 0 && quote.totalAmount() >= 0);
        require(BigInteger.valueOf(quote.baseAmount()).add(BigInteger.valueOf(quote.mandatoryFeesAmount()))
                .equals(BigInteger.valueOf(quote.totalAmount())));
        require(quote.distanceMeters() >= 0 && quote.availableRoomCount() >= stay.rooms());
        require(quote.maxAdults() > 0 && (long) quote.maxAdults() * stay.rooms() >= stay.adults());
        require(quote.checkedAt() != null && quote.expiresAt() != null
                && quote.expiresAt().isAfter(quote.checkedAt()));
        require(Set.of("NON_REFUNDABLE", "FREE_CANCELLATION", "PARTIAL_REFUND").contains(quote.cancellationPolicy()));
    }

    private static Map<String, Object> stayWire(Stay stay, boolean rooms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkInDate", stay.checkInDate().toString());
        body.put("checkOutDate", stay.checkOutDate().toString()); body.put("adults", stay.adults());
        if (rooms) body.put("rooms", stay.rooms());
        return body;
    }

    private static Stay readStay(Map<?, ?> body) {
        exact(body, Set.of("checkInDate", "checkOutDate", "adults", "rooms"));
        Stay stay = new Stay(date(body.get("checkInDate")), date(body.get("checkOutDate")),
                integer(body.get("adults"), 1, Short.MAX_VALUE), integer(body.get("rooms"), 1, Short.MAX_VALUE));
        validateStay(stay);
        return stay;
    }

    private static void validateStay(Stay stay) {
        require(stay != null && stay.checkInDate() != null && stay.checkOutDate() != null
                && stay.checkOutDate().isAfter(stay.checkInDate())
                && stay.adults() > 0 && stay.adults() <= Short.MAX_VALUE && stay.rooms() > 0 && stay.rooms() <= Short.MAX_VALUE);
    }

    static Map<?, ?> object(Object value) {
        require(value instanceof Map<?, ?>); return (Map<?, ?>) value;
    }
    static List<?> array(Object value, int min, int max) {
        require(value instanceof List<?>);
        List<?> list = (List<?>) value; require(list.size() >= min && list.size() <= max); return list;
    }
    static String text(Object value, int max) {
        require(value instanceof String);
        String text = (String) value;
        require(!text.isBlank() && text.codePointCount(0, text.length()) <= max && text.indexOf(0) < 0);
        return text;
    }
    private static void identifier(String value, int max) { text(value, max); }
    static long amount(Object value) {
        require(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger);
        long amount = value instanceof BigInteger big ? big.longValueExact() : ((Number) value).longValue();
        require(amount >= 0); return amount;
    }
    static int integer(Object value, int min, int max) {
        long amount = amount(value); require(amount >= min && amount <= max); return (int) amount;
    }
    private static LocalDate date(Object value) {
        String text = text(value, 10); require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")); return LocalDate.parse(text);
    }
    private static OffsetDateTime timestamp(Object value) {
        String text = text(value, 64);
        require(text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})"));
        return OffsetDateTime.parse(text);
    }
    private static List<String> textArray(Object value, int min, int max, int stringMax) {
        return textArray(value, min, max, stringMax, true);
    }
    private static List<String> textArray(Object value, int min, int max, int stringMax, boolean unique) {
        List<String> items = array(value, min, max).stream().map(item -> text(item, stringMax)).toList();
        require(!unique || new HashSet<>(items).size() == items.size()); return items;
    }
    private static void exact(Map<?, ?> body, Set<String> fields) { require(body.keySet().equals(fields)); }
    static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid quote contract"); }
}
