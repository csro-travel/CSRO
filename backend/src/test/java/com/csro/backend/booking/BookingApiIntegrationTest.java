package com.csro.backend.booking;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-001 고객 예약 상세 조회 — {@code GET /api/v1/bookings/{bookingId}}.
 * <p>조회·인가 핵심과 DB 무변경을 검증하며, 공통 인증 연결은 검증하지 않는다.
 * Runs against the PostgreSQL database configured by the local profile.
 * Every test creates its own fixture and rolls it back; existing YB-001 is not used.
 * MockMvc principal() supplies a test identity only. These tests do not verify
 * Bearer token issuance, token validation, or an external authentication provider.
 *
 * @see <a href="https://app.notion.com/p/1f32f2597c578386a3c181f38463f607">API-001 명세</a>
 */
@DisplayName("API-001 고객 예약 상세 조회 | PostgreSQL 조회·인가 통합 테스트")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class BookingApiIntegrationTest {

    // Constant identifiers from the shared business schema, not request input.
    private static final List<String> BUSINESS_TABLES = List.of(
            "users", "hotels", "bookings", "booking_nights", "risk_assessments",
            "jobs", "evidence_records", "agent_analyses", "evidence_nights",
            "cases", "case_tasks", "incident_reports", "case_actions", "chat_messages",
            "alternative_quotes", "quote_rankings", "offers", "offer_items",
            "alternative_selections", "replacement_requests", "checkin_confirmations"
    );

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbcClient;

    private String bookingCode;
    private String hotelCode;
    private String ownerSubject;
    private String otherSubject;
    private String counselorSubject;
    private String inactiveSubject;
    private long bookingId;

    @BeforeEach
    void createIndependentFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        bookingCode = "TEST-B-" + suffix;
        hotelCode = "TEST-H-" + suffix;
        ownerSubject = "test-owner-" + suffix;
        otherSubject = "test-other-" + suffix;
        counselorSubject = "test-counselor-" + suffix;
        inactiveSubject = "test-inactive-" + suffix;

        long ownerId = insertUser(ownerSubject, "CUSTOMER", true);
        insertUser(otherSubject, "CUSTOMER", true);
        insertUser(counselorSubject, "COUNSELOR", true);
        insertUser(inactiveSubject, "CUSTOMER", false);

        long hotelId = jdbcClient.sql("""
                INSERT INTO hotels (hotel_code, name, time_zone, data_source)
                VALUES (:code, '테스트 호텔', 'Asia/Seoul', 'SIMULATED')
                RETURNING id
                """)
                .param("code", hotelCode)
                .query(Long.class)
                .single();

        bookingId = jdbcClient.sql("""
                INSERT INTO bookings (
                    booking_code, customer_id, hotel_id, supplier_code, supplier_booking_id,
                    supplier_status, check_in_date, check_out_date, adults, rooms,
                    original_total_amount, currency
                ) VALUES (
                    :code, :customerId, :hotelId, 'MOCK_SUPPLIER', :supplierBookingId,
                    'CONFIRMED', DATE '2026-11-10', DATE '2026-11-13', 2, 1,
                    450000, 'KRW'
                )
                RETURNING id
                """)
                .param("code", bookingCode)
                .param("customerId", ownerId)
                .param("hotelId", hotelId)
                .param("supplierBookingId", "SUP-TEST-" + suffix)
                .query(Long.class)
                .single();

        jdbcClient.sql("""
                INSERT INTO booking_nights (booking_id, stay_date)
                SELECT :bookingId, DATE '2026-11-10' + day_offset
                FROM generate_series(0, 2) AS days(day_offset)
                """)
                .param("bookingId", bookingId)
                .update();
    }

    @Test
    @DisplayName("API-001 | 본인 예약 조회 200: 고객 DTO와 활성 사건 없음")
    void ownerReceivesCustomerDtoWithNoActiveCase() throws Exception {
        MvcResult result = asOwner()
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.bookingId").value(bookingCode))
                .andExpect(jsonPath("$.hotel.hotelId").value(hotelCode))
                .andExpect(jsonPath("$.hotel.name").value("테스트 호텔"))
                .andExpect(jsonPath("$.stay.checkInDate").value("2026-11-10"))
                .andExpect(jsonPath("$.stay.checkOutDate").value("2026-11-13"))
                .andExpect(jsonPath("$.stay.nights").value(3))
                .andExpect(jsonPath("$.stay.adults").value(2))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.originalPrice.amount").value(450000))
                .andExpect(jsonPath("$.originalPrice.currency").value("KRW"))
                .andExpect(jsonPath("$.support.activeCaseId").value((Object) null))
                .andExpect(jsonPath("$.support.needsAttention").value(false))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        for (String internalField : List.of(
                "riskScore", "modelVersion", "featureSnapshot", "originalContent",
                "rawText", "rawPayload", "evidence", "customerId", "subjectKey"
        )) {
            assertFalse(body.contains("\"" + internalField + "\""),
                    "Customer response must not expose " + internalField);
        }
    }

    @Test
    @DisplayName("API-001 | 타인 예약과 없는 예약은 동일한 404")
    void anotherCustomersBookingAndMissingBookingHaveSameNotFoundResponse() throws Exception {
        MvcResult notOwned = problem(
                mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                        .principal(identity(otherSubject))),
                404, "BOOKING_NOT_FOUND"
        ).andReturn();

        MvcResult missing = problem(
                mockMvc.perform(get("/api/v1/bookings/{bookingId}", "TEST-MISSING")
                        .principal(identity(ownerSubject))),
                404, "BOOKING_NOT_FOUND"
        ).andReturn();

        String notOwnedDetail = JsonPath.read(notOwned.getResponse().getContentAsString(), "$.detail");
        String missingDetail = JsonPath.read(missing.getResponse().getContentAsString(), "$.detail");
        assertNotNull(notOwnedDetail);
        assertEquals(notOwnedDetail, missingDetail,
                "Do not reveal whether another customer's booking exists");
    }

    @Test
    @DisplayName("API-001 | 상담사의 고객 예약 API 접근은 403")
    void counselorCannotReadCustomerBookingApi() throws Exception {
        problem(mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                .principal(identity(counselorSubject))), 403, "FORBIDDEN_ROLE");
    }

    @Test
    @DisplayName("API-001 | 인증 Principal 누락은 401")
    void missingPrincipalIsUnauthenticated() throws Exception {
        problem(mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)),
                401, "UNAUTHENTICATED")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    @DisplayName("API-001 | 등록되지 않은 인증 주체는 401")
    void unknownPrincipalIsUnauthenticated() throws Exception {
        problem(mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                .principal(identity("unknown-" + UUID.randomUUID()))),
                401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("API-001 | 비활성 고객은 401")
    void inactiveCustomerIsUnauthenticated() throws Exception {
        problem(mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                .principal(identity(inactiveSubject))), 401, "UNAUTHENTICATED");
    }

    @Test
    @DisplayName("API-001 | 임의 요청 헤더로 인증 주체를 만들 수 없음")
    void clientHeadersCannotManufactureVerifiedPrincipal() throws Exception {
        problem(mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerSubject)
                .header("X-User", ownerSubject)
                .header("X-User-Id", ownerSubject)
                .header("X-Customer-Subject", ownerSubject)),
                401, "UNAUTHENTICATED");
    }

    @ParameterizedTest
    @DisplayName("API-001 | 활성 사건을 연결해도 공급사 계약 상태는 유지")
    @ValueSource(strings = {"OPEN", "IN_PROGRESS", "WAITING"})
    void activeCaseIsLinkedWithoutChangingContractStatus(String caseStatus) throws Exception {
        String caseCode = insertCase(caseStatus);
        asOwner()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support.activeCaseId").value(caseCode))
                .andExpect(jsonPath("$.support.needsAttention").value(true))
                .andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
    }

    @Test
    @DisplayName("API-001 | 종료 사건은 활성 사건으로 반환하지 않음")
    void closedCaseIsNotReturnedAsActive() throws Exception {
        insertCase("CLOSED");
        asOwner()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support.activeCaseId").value((Object) null))
                .andExpect(jsonPath("$.support.needsAttention").value(false));
    }

    @Test
    @DisplayName("API-001 | 과거 종료 사건과 현재 활성 사건을 구분")
    void closedHistoricalCaseDoesNotHideCurrentActiveCase() throws Exception {
        insertCase("CLOSED");
        String activeCaseCode = insertCase("OPEN");
        asOwner()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support.activeCaseId").value(activeCaseCode))
                .andExpect(jsonPath("$.support.needsAttention").value(true));
    }

    @Test
    @DisplayName("API-001 | 반복 GET은 업무 데이터를 생성·변경하지 않음")
    void repeatedGetDoesNotCreateOrModifyBusinessData() throws Exception {
        insertCase("WAITING");
        Map<String, String> before = businessSnapshot();

        asOwner().andExpect(status().isOk());
        asOwner().andExpect(status().isOk());
        problem(mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                .principal(identity(otherSubject))), 404, "BOOKING_NOT_FOUND");

        assertEquals(before, businessSnapshot(),
                "Booking GET must not modify bookings, cases, jobs, evidence, or other business rows");
    }

    private ResultActions asOwner() throws Exception {
        return mockMvc.perform(get("/api/v1/bookings/{bookingId}", bookingCode)
                .principal(identity(ownerSubject)));
    }

    private static Principal identity(String subjectKey) {
        return () -> subjectKey;
    }

    private ResultActions problem(ResultActions action, int expectedStatus, String code) throws Exception {
        return action
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.detail", not(emptyOrNullString())))
                .andExpect(jsonPath("$.traceId", not(emptyOrNullString())));
    }

    private long insertUser(String subject, String role, boolean active) {
        return jdbcClient.sql("""
                INSERT INTO users (subject_key, display_name, role, is_active)
                VALUES (:subject, 'API-001 테스트 사용자', :role, :active)
                RETURNING id
                """)
                .param("subject", subject)
                .param("role", role)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    private String insertCase(String caseStatus) {
        String caseCode = "TEST-C-" + UUID.randomUUID().toString().replace("-", "");
        if ("CLOSED".equals(caseStatus)) {
            jdbcClient.sql("""
                    INSERT INTO cases (case_code, booking_id, status, close_reason, closed_at)
                    VALUES (:code, :bookingId, 'CLOSED', 'TEST_RESOLVED', now())
                    """)
                    .param("code", caseCode)
                    .param("bookingId", bookingId)
                    .update();
        } else {
            jdbcClient.sql("""
                    INSERT INTO cases (case_code, booking_id, status)
                    VALUES (:code, :bookingId, :status)
                    """)
                    .param("code", caseCode)
                    .param("bookingId", bookingId)
                    .param("status", caseStatus)
                    .update();
        }
        return caseCode;
    }

    private Map<String, String> businessSnapshot() {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : BUSINESS_TABLES) {
            // Hashes include existing row values, so UPDATEs are detected as well
            // as INSERTs/DELETEs without printing any raw business data.
            String hash = jdbcClient.sql(
                    "SELECT md5(coalesce(string_agg(to_jsonb(t)::text, '' ORDER BY to_jsonb(t)::text), '')) FROM "
                            + table + " t"
            ).query(String.class).single();
            snapshot.put(table, hash);
        }
        return snapshot;
    }
}
