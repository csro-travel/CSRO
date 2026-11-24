package com.csro.backend.quotes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import com.csro.backend.quotes.QuoteContracts.RankInput;
import com.csro.backend.quotes.QuoteContracts.SearchInput;
import com.csro.backend.quotes.QuoteContracts.SearchResult;
import com.csro.backend.quotes.QuoteContracts.Stay;
import com.csro.backend.quotes.QuoteIntegrationException.Kind;

/** API-025/028 wire boundary and transaction guard tests; no business DB access. */
class QuoteHttpClientsTest {
    private static final String BASE = "http://127.0.0.1:8001";
    private static final String SEARCH_PATH = BASE + "/mock-supplier/v1/availability-searches";
    private static final String RANKING_PATH = BASE + "/internal/v1/alternative-rankings";
    private static final String TOKEN = "quote-client-unit-fixture";
    private static final Stay STAY = new Stay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 13), 2, 1);
    private static final String SEARCH_REQUEST = """
            {"searchRequestId":"SEARCH-001","originPropertyId":"HOTEL-001",
             "stay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1},
             "filters":{"radiusMeters":3000,"currency":"KRW","maxAverageNightlyAmount":300000}}
            """;
    // Independent source-contract transcription; not derived from Mock implementation code.
    private static final String QUOTE = """
            {"quoteId":"QUOTE-A","source":"SIMULATED_SUPPLIER","propertyId":"HOTEL-A","propertyName":"A호텔",
             "distanceMeters":800,"roomTypeId":"ROOM-A-DOUBLE","roomName":"디럭스 더블",
             "ratePlanId":"RATE-A-BREAKFAST","maxAdults":2,"breakfastIncluded":true,"availableRoomCount":1,
             "price":{"baseAmount":450000,"mandatoryFeesAmount":30000,"totalAmount":480000,"currency":"KRW"},
             "cancellationPolicy":"NON_REFUNDABLE","checkedAt":"2026-11-09T10:15:00+09:00",
             "expiresAt":"2026-11-09T10:25:00+09:00"}
            """;
    private static final String RANKING = """
            {"caseId":"C-001","rankingVersion":"alternative-ranker-0.1","rankedQuoteIds":["QUOTE-A"],
             "reasons":[{"quoteId":"QUOTE-A","reasonCodes":["NEAREST"],"summary":"기존 호텔에서 가장 가까운 후보입니다."}],
             "warnings":["예약 전 가격과 재고 재확인이 필요합니다."]}
            """;

    private MockRestServiceServer server;
    private RestClient restClient;
    private QuoteSupplierClient supplier;
    private QuoteRankingClient ranker;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        supplier = new QuoteSupplierClient(restClient, TOKEN);
        ranker = new QuoteRankingClient(restClient, TOKEN);
    }

    @AfterEach
    void verifyNoUnexpectedOrRetriedHttp() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        server.verify();
    }

    @Test
    void preservesTheValidatedThreeNightPriceAndVirtualQuoteTime() {
        server.expect(requestTo(SEARCH_PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json(SEARCH_REQUEST, JsonCompareMode.STRICT))
                .andRespond(withSuccess(searchResponse(QUOTE), MediaType.APPLICATION_JSON));

        SearchResult result = supplier.search(searchInput());

        assertEquals(STAY, result.stay());
        assertEquals(480000L, result.quotes().getFirst().totalAmount());
        assertEquals(450000L, result.quotes().getFirst().baseAmount());
        assertEquals(30000L, result.quotes().getFirst().mandatoryFeesAmount());
        assertEquals("2026-11-09T10:15+09:00", result.checkedAt().toString());
        assertEquals("SIMULATED_SUPPLIER", result.quotes().getFirst().source());
    }

    @Test
    void acceptsEmptySearchAsAResultWithoutChangingTheRequestedPeriod() {
        expectSearch(searchResponse(""));
        SearchResult result = supplier.search(searchInput());
        assertEquals(STAY, result.stay());
        assertEquals(List.of(), result.quotes());
    }

    @Test
    void rejectsInconsistentAmountsWrongStayCoercionAndDuplicatedSupplierQuoteIds() {
        List<String> invalidResponses = List.of(
                searchResponse(QUOTE.replace("\"totalAmount\":480000", "\"totalAmount\":480001")),
                searchResponse(QUOTE).replace("\"checkOutDate\":\"2026-11-13\"", "\"checkOutDate\":\"2026-11-11\""),
                searchResponse(QUOTE.replace("\"totalAmount\":480000", "\"totalAmount\":\"480000\"")),
                searchResponse(QUOTE.replace("\"availableRoomCount\":1", "\"availableRoomCount\":true")),
                searchResponse(QUOTE.replace("\"propertyName\":\"A호텔\"",
                        "\"propertyName\":\"A호텔\",\"amenityCodes\":[\"POOL\",\"UNVERIFIED_FACILITY\"]")),
                searchResponse(QUOTE + "," + QUOTE),
                searchResponse(QUOTE.replace("\"expiresAt\":\"2026-11-09T10:25:00+09:00\"",
                        "\"expiresAt\":\"2026-11-09T10:15:00+09:00\"")));
        for (String response : invalidResponses) {
            expectSearch(response);
        }
        for (String response : invalidResponses) {
            assertEquals(Kind.INVALID_RESPONSE,
                    assertThrows(QuoteIntegrationException.class, () -> supplier.search(searchInput())).kind());
        }
    }

    @Test
    void mapsOnlySuppliedSnapshotFactsToTheAgentAndReceivesOnlyInputQuoteIds() {
        expectSearch(searchResponse(QUOTE));
        server.expect(requestTo(RANKING_PATH)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(content().json("""
                        {"caseId":"C-001","bookingId":"YB-001",
                         "requestedStay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2},
                         "originalStayTotal":{"amount":450000,"currency":"KRW"},
                         "quotes":[{"quoteId":"QUOTE-A","hotelName":"A호텔","checkInDate":"2026-11-10",
                           "checkOutDate":"2026-11-13","adults":2,"distanceMeters":800,"totalAmount":480000,
                           "currency":"KRW","checkedAt":"2026-11-09T10:15:00+09:00"}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(RANKING, MediaType.APPLICATION_JSON));

        var result = ranker.rank(rankInput(supplier.search(searchInput())));

        assertEquals(List.of("QUOTE-A"), result.rankedQuoteIds());
        assertEquals("C-001", result.caseId());
        assertFalse(result.responseJson().contains("confirmedPrice"));
    }

    @Test
    void rejectsAlienDuplicateOrPriceInventingAgentOutputs() {
        List<String> invalidResponses = List.of(
                RANKING.replace("QUOTE-A", "ALIEN-QUOTE"),
                RANKING.replace("\"rankedQuoteIds\":[\"QUOTE-A\"]", "\"rankedQuoteIds\":[\"QUOTE-A\",\"QUOTE-A\"]"),
                RANKING.replace("\"caseId\":\"C-001\"", "\"caseId\":\"C-002\""),
                RANKING.replace("\"rankingVersion\"", "\"totalAmount\":1,\"rankingVersion\""));
        for (String response : invalidResponses) {
            expectSearch(searchResponse(QUOTE));
            server.expect(requestTo(RANKING_PATH)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        for (String response : invalidResponses) {
            RankInput input = rankInput(supplier.search(searchInput()));
            assertEquals(Kind.INVALID_RESPONSE,
                    assertThrows(QuoteIntegrationException.class, () -> ranker.rank(input)).kind());
        }
    }

    @Test
    void preservesAnUpstreamFailureClassWithoutLeakingItsResponseOrCredential() {
        server.expect(requestTo(SEARCH_PATH)).andRespond(withRawStatus(503)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"type":"about:blank","status":503,"title":"Service Unavailable",
                         "code":"SUPPLIER_UNAVAILABLE","traceId":"req-test","detail":"private-details"}
                        """));
        QuoteIntegrationException error = assertThrows(QuoteIntegrationException.class,
                () -> supplier.search(searchInput()));
        assertEquals(Kind.UNAVAILABLE, error.kind());
        assertEquals(503, error.upstreamStatus());
        assertFalse(error.getMessage().contains("private-details"));
        assertFalse(error.getMessage().contains(TOKEN));
        assertNull(error.getCause());
    }

    @Test
    void classifiesAgentAuthenticationFailureWithoutRetryingOrInventingARanking() {
        expectSearch(searchResponse(QUOTE));
        server.expect(requestTo(RANKING_PATH)).andRespond(withRawStatus(401)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"type":"about:blank","status":401,"title":"Unauthorized",
                         "code":"UNAUTHENTICATED_SERVICE","traceId":"req-auth","detail":"private-details"}
                        """));
        RankInput input = rankInput(supplier.search(searchInput()));
        QuoteIntegrationException error = assertThrows(QuoteIntegrationException.class, () -> ranker.rank(input));
        assertEquals(Kind.AUTHENTICATION, error.kind());
        assertEquals(401, error.upstreamStatus());
        assertFalse(error.getMessage().contains("private-details"));
    }

    @Test
    void omitsTheOriginalThreeNightTotalWhenComparingAnExplicitOneNightStay() {
        Stay oneNight = new Stay(STAY.checkInDate(), LocalDate.of(2026, 11, 11), 2, 1);
        String response = searchResponse(QUOTE).replace("2026-11-13", "2026-11-11")
                .replace("QUOTE-A", "QUOTE-A-FIRST")
                .replace("450000", "150000").replace("30000", "10000").replace("480000", "160000");
        expectSearch(response);
        server.expect(requestTo(RANKING_PATH)).andExpect(content().json("""
                        {"caseId":"C-001","bookingId":"YB-001",
                         "requestedStay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-11","adults":2},
                         "quotes":[{"quoteId":"QUOTE-A-FIRST","hotelName":"A호텔","checkInDate":"2026-11-10",
                           "checkOutDate":"2026-11-11","adults":2,"distanceMeters":800,"totalAmount":160000,
                           "currency":"KRW","checkedAt":"2026-11-09T10:15:00+09:00"}]}
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess(RANKING.replace("QUOTE-A", "QUOTE-A-FIRST"), MediaType.APPLICATION_JSON));
        var search = supplier.search(new SearchInput("SEARCH-001", "HOTEL-001", oneNight, 3000, 300000L));
        var result = ranker.rank(new RankInput("C-001", "YB-001", oneNight, null, search.quotes()));
        assertEquals(List.of("QUOTE-A-FIRST"), result.rankedQuoteIds());
    }

    @Test
    void rejectsMissingAuthenticationBeforeAnyNetworkCall() {
        supplier = new QuoteSupplierClient(restClient, " ");
        assertEquals(Kind.NO_SERVICE_TOKEN,
                assertThrows(QuoteIntegrationException.class, () -> supplier.search(searchInput())).kind());
    }

    @Test
    void refusesToStartSupplierHttpInsideAnActiveBusinessTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals(Kind.TRANSACTION_ACTIVE,
                assertThrows(QuoteIntegrationException.class, () -> supplier.search(searchInput())).kind());
    }

    @Test
    void refusesToStartAgentHttpInsideAnActiveBusinessTransaction() {
        expectSearch(searchResponse(QUOTE));
        RankInput input = rankInput(supplier.search(searchInput()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertEquals(Kind.TRANSACTION_ACTIVE,
                assertThrows(QuoteIntegrationException.class, () -> ranker.rank(input)).kind());
    }

    @Test
    void stopsReadingAnOversizedSupplierBody() {
        expectSearch(" ".repeat(2 * 1024 * 1024));
        assertEquals(Kind.RESPONSE_TOO_LARGE,
                assertThrows(QuoteIntegrationException.class, () -> supplier.search(searchInput())).kind());
    }

    private void expectSearch(String response) {
        server.expect(requestTo(SEARCH_PATH)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static SearchInput searchInput() {
        return new SearchInput("SEARCH-001", "HOTEL-001", STAY, 3000, 300000L);
    }

    private static RankInput rankInput(SearchResult search) {
        return new RankInput("C-001", "YB-001", STAY, 450000L, search.quotes());
    }

    private static String searchResponse(String quotes) {
        return """
                {"searchRequestId":"SEARCH-001","searchStatus":"COMPLETED",
                 "stay":{"checkInDate":"2026-11-10","checkOutDate":"2026-11-13","adults":2,"rooms":1},
                 "checkedAt":"2026-11-09T10:15:00+09:00","currency":"KRW","quotes":[%s]}
                """.formatted(quotes);
    }
}
