package com.csro.backend.cases;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "CHATTING_API_INTEGRATION_TEST", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("case-messages-integration")
@ContextConfiguration(loader = CaseMessagesIntegrationTest.IsolatedDatabaseLoader.class)
@Transactional
class CaseMessagesIntegrationTest {

    private static final String MESSAGES = "/api/v1/cases/{caseId}/messages";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OffsetDateTime FIRST = OffsetDateTime.parse("2026-11-09T10:15:00+09:00");
    private static final OffsetDateTime TIE = FIRST.plusMinutes(1);

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private String suffix;
    private String owner;
    private String otherCustomer;
    private String counselor;
    private long ownerId;
    private long counselorId;
    private long hotelId;
    private TestCase closedCase;
    private TestCase openCase;

    @BeforeEach
    void createCaseFixtures() {
        suffix = UUID.randomUUID().toString().replace("-", "");
        owner = "test-owner-" + suffix;
        otherCustomer = "test-other-" + suffix;
        counselor = "test-counselor-" + suffix;
        ownerId = insertUser(owner, "CUSTOMER");
        insertUser(otherCustomer, "CUSTOMER");
        counselorId = insertUser(counselor, "COUNSELOR");
        hotelId = jdbc.sql("""
                INSERT INTO hotels (hotel_code, name) VALUES (:code, '테스트 호텔') RETURNING id
                """).param("code", "H-" + suffix).query(Long.class).single();
        long bookingId = jdbc.sql("""
                INSERT INTO bookings (booking_code, customer_id, hotel_id, supplier_booking_id,
                    check_in_date, check_out_date, adults, original_total_amount)
                VALUES (:code, :owner, :hotel, :code, DATE '2026-11-10', DATE '2026-11-13', 2, 450000)
                RETURNING id
                """).param("code", "B-" + suffix).param("owner", ownerId).param("hotel", hotelId)
                .query(Long.class).single();
        closedCase = insertCase(bookingId, "CLOSED");
        openCase = insertCase(bookingId, "OPEN");
    }

    @Test
    void readsClosedCaseInStableTimeOrderAndIncludesHistoricalOfferCards() throws Exception {
        insertOffer();
        String oldest = messageCode("OLD");
        String olderTie = messageCode("TIE");
        String offer = messageCode("OFR");
        String newest = messageCode("NEW");
        String beforeRead = historySnapshot();

        mvc.perform(get(MESSAGES, closedCase.code()).principal(() -> owner).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.caseId").value(closedCase.code()))
                .andExpect(jsonPath("$.items[*].messageId", contains(offer, newest)))
                .andExpect(jsonPath("$.items[0].senderType").value("COUNSELOR"))
                .andExpect(jsonPath("$.items[0].messageType").value("OFFER"))
                .andExpect(jsonPath("$.items[0].body").value(nullValue()))
                .andExpect(jsonPath("$.items[0].offer.offerId").value("OFFER-" + suffix))
                .andExpect(jsonPath("$.items[0].offer.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.items[0].offer.items[*].displayOrder", contains(1, 2, 3)))
                .andExpect(jsonPath("$.items[0].offer.items[*].quoteId", contains(quoteCode(1), quoteCode(2), quoteCode(3))))
                .andExpect(jsonPath("$.items[0].offer.items[0].totalAmount").value(480000))
                .andExpect(jsonPath("$.items[0].offer.items[0].priceDifference").value(nullValue()))
                .andExpect(jsonPath("$.items[1].senderType").value("SYSTEM"))
                .andExpect(jsonPath("$.items[1].offer").value(nullValue()))
                .andExpect(jsonPath("$.pagination.hasMoreBefore").value(true))
                .andExpect(jsonPath("$.pagination.nextBeforeMessageId").value(offer));

        assertEquals(beforeRead, historySnapshot());
        insertMessage(closedCase.id(), messageCode("ADD"), null,
                "SYSTEM", "SYSTEM", "페이지 조회 이후 안내", null, TIE.plusMinutes(2));
        String beforePreviousPage = historySnapshot();

        mvc.perform(get(MESSAGES, closedCase.code()).principal(() -> counselor)
                        .param("limit", "2").param("beforeMessageId", offer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].messageId", contains(oldest, olderTie)))
                .andExpect(jsonPath("$.items[0].senderType").value("CUSTOMER"))
                .andExpect(jsonPath("$.items[0].body").value("테스트 메시지"))
                .andExpect(jsonPath("$.items[0].senderUserId").doesNotExist())
                .andExpect(jsonPath("$.pagination.hasMoreBefore").value(false))
                .andExpect(jsonPath("$.pagination.nextBeforeMessageId").value(nullValue()));

        assertEquals(beforePreviousPage, historySnapshot());
    }

    @Test
    void readsCurrentChoiceOutsideThePageAndKeepsTheSentComparisonBasis() throws Exception {
        insertOffer();
        String offerCode = "OFFER-" + suffix;
        String selectionCode = "SEL-" + suffix;
        jdbc.sql("UPDATE offers SET status = 'ACTIVE' WHERE case_id = :caseId")
                .param("caseId", closedCase.id()).update();
        jdbc.sql("""
                INSERT INTO alternative_selections (selection_code, case_id, offer_id, quote_id,
                    selected_by, selected_at, idempotency_key, request_hash)
                SELECT :selection, o.case_id, o.id, q.id, :owner, :selectedAt, :selection, repeat('1', 64)
                FROM offers o JOIN alternative_quotes q ON q.case_id = o.case_id
                WHERE o.offer_code = :offer AND q.quote_code = :quote
                """).param("selection", selectionCode).param("owner", ownerId)
                .param("selectedAt", TIE.plusMinutes(1)).param("offer", offerCode)
                .param("quote", quoteCode(1)).update();
        jdbc.sql("""
                INSERT INTO case_actions (case_id, actor_id, action_type, details, acted_at, booking_id)
                VALUES (:caseId, :actor, 'OFFER_SENT', CAST(:details AS jsonb), :sentAt, :booking)
                """).param("caseId", closedCase.id()).param("actor", counselorId)
                .param("details", JSON.writeValueAsString(Map.of("protocol", "offer-selection-v1",
                        "offerId", offerCode, "coverageMode", "FULL_STAY", "originalAmount", 450000)))
                .param("sentAt", TIE).param("booking", closedCase.bookingId()).update();
        jdbc.sql("UPDATE bookings SET original_total_amount = 900000 WHERE id = :booking")
                .param("booking", closedCase.bookingId()).update();

        mvc.perform(get(MESSAGES, closedCase.code()).principal(() -> owner).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].messageType").value("SYSTEM"))
                .andExpect(jsonPath("$.activeOfferId").value(offerCode))
                .andExpect(jsonPath("$.currentSelection.selectionId").value(selectionCode))
                .andExpect(jsonPath("$.currentSelection.offerId").value(offerCode))
                .andExpect(jsonPath("$.currentSelection.quoteId").value(quoteCode(1)))
                .andExpect(jsonPath("$.effectiveAt").isNotEmpty())
                .andExpect(jsonPath("$.clockMode").value("REAL_TIME"));

        mvc.perform(get(MESSAGES, closedCase.code()).principal(() -> owner).param("limit", "1")
                        .param("beforeMessageId", messageCode("NEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].offer.items[0].priceDifference").value(30000))
                .andExpect(jsonPath("$.activeOfferId").value(offerCode))
                .andExpect(jsonPath("$.currentSelection.selectionId").value(selectionCode));
    }

    @Test
    void checksCaseAccessBeforeValidatingTheCursor() throws Exception {
        String foreignCursor = messageCode("OTHER");
        insertMessage(openCase.id(), foreignCursor, ownerId, "CUSTOMER", "TEXT", "테스트 메시지", null, FIRST);

        expectProblem(mvc.perform(get(MESSAGES, closedCase.code())), 401, "UNAUTHENTICATED")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        expectProblem(mvc.perform(get(MESSAGES, closedCase.code()).principal(() -> otherCustomer)
                .param("beforeMessageId", foreignCursor)), 404, "CASE_NOT_FOUND");
        expectProblem(mvc.perform(get(MESSAGES, newCaseCode()).principal(() -> owner)
                .param("beforeMessageId", foreignCursor)), 404, "CASE_NOT_FOUND");
        for (String cursor : new String[]{foreignCursor, messageCode("NONE"), ""}) {
            expectProblem(mvc.perform(get(MESSAGES, closedCase.code()).principal(() -> owner)
                    .param("beforeMessageId", cursor)), 400, "INVALID_MESSAGE_CURSOR");
        }
    }

    @Test
    void returnsAnEmptyHistoryAndRejectsOutOfRangeLimit() throws Exception {
        mvc.perform(get(MESSAGES, openCase.code()).principal(() -> owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(openCase.code()))
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.pagination.hasMoreBefore").value(false))
                .andExpect(jsonPath("$.pagination.nextBeforeMessageId").value(nullValue()));

        for (String limit : new String[]{"", "51"}) {
            expectProblem(mvc.perform(get(MESSAGES, openCase.code()).principal(() -> owner).param("limit", limit)),
                    400, "INVALID_PAGE_SIZE");
        }
        expectProblem(mvc.perform(get(MESSAGES, "C-" + "1".repeat(39)).principal(() -> owner)),
                400, "INVALID_CASE_ID");

        String detailPath = "/api/v1/cases/{caseId}";
        expectProblem(mvc.perform(get(detailPath, openCase.code()).principal(() -> owner)), 403, "FORBIDDEN_ROLE");
        mvc.perform(get(detailPath, openCase.code()).principal(() -> counselor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(openCase.code()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void commitsOriginalTextFromBothRolesInEveryActiveState() throws Exception {
        String customerBody = "  체크인 시간을 확인해 주세요.\n늦게 도착할 예정입니다.  ";
        String counselorBody = "호텔에 확인하고 안내하겠습니다.";

        String customerMessageId = sendText(owner, "CUSTOMER", customerBody);
        setCaseStatus("IN_PROGRESS");
        String counselorMessageId = sendText(counselor, "COUNSELOR", counselorBody);
        setCaseStatus("WAITING");
        String repeatedMessageId = sendText(owner, "CUSTOMER", customerBody);

        assertNotEquals(customerMessageId, repeatedMessageId);
        assertEquals(ownerId, storedSenderId(customerMessageId));
        assertEquals(counselorId, storedSenderId(counselorMessageId));
        assertEquals(ownerId, storedSenderId(repeatedMessageId));
        mvc.perform(get(MESSAGES, openCase.code()).principal(() -> owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(openCase.code()))
                .andExpect(jsonPath("$.items[*].messageId",
                        containsInAnyOrder(customerMessageId, counselorMessageId, repeatedMessageId)))
                .andExpect(jsonPath("$.items[?(@.messageId == '%s')].body", customerMessageId).value(contains(customerBody)))
                .andExpect(jsonPath("$.items[?(@.messageId == '%s')].body", counselorMessageId).value(contains(counselorBody)))
                .andExpect(jsonPath("$.items[?(@.messageId == '%s')].body", repeatedMessageId).value(contains(customerBody)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsUnauthorizedClosedAndInvalidSendsWithoutWritingMessages() throws Exception {
        String validBody = "{\"body\":\"전송할 메시지\"}";

        expectProblem(mvc.perform(post(MESSAGES, openCase.code()).contentType(MediaType.APPLICATION_JSON)
                .content(validBody)), 401, "UNAUTHENTICATED")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        expectProblem(postMessage(openCase.code(), otherCustomer, validBody), 404, "CASE_NOT_FOUND");
        expectProblem(postMessage(closedCase.code(), otherCustomer, validBody), 404, "CASE_NOT_FOUND");
        expectProblem(postMessage(newCaseCode(), owner, validBody), 404, "CASE_NOT_FOUND");
        jdbc.sql("UPDATE users SET is_active = false WHERE subject_key = :subject")
                .param("subject", otherCustomer).update();
        expectProblem(postMessage(openCase.code(), otherCustomer, validBody), 401, "UNAUTHENTICATED");
        expectProblem(postMessage(closedCase.code(), counselor, validBody), 403, "CASE_CLOSED");

        expectProblem(postMessage("C-" + "1".repeat(39), owner, validBody), 400, "INVALID_CASE_ID");
        for (String invalidBody : new String[]{"{}", "{\"body\":123}", "{\"body\":\"  \"}",
                "{\"body\":\"널 문자 \\u0000\"}", "{\"body\":\"위조 요청\",\"senderType\":\"COUNSELOR\"}",
                "{\"body\":\"첫 값\",\"body\":\"둘째 값\"}", validBody + "{}", "{\"body\":"}) {
            expectProblem(postMessage(openCase.code(), owner, invalidBody), 400, "INVALID_MESSAGE_BODY");
        }
        expectProblem(mvc.perform(post(MESSAGES, openCase.code()).principal(() -> owner)
                .contentType(MediaType.TEXT_PLAIN).content("텍스트 요청")), 415, "UNSUPPORTED_MEDIA_TYPE");

        long messageCount = jdbc.sql("SELECT count(*) FROM chat_messages WHERE case_id IN (:openId, :closedId)")
                .param("openId", openCase.id()).param("closedId", closedCase.id()).query(Long.class).single();
        assertEquals(0L, messageCount);
    }

    private String sendText(String subject, String senderType, String body) throws Exception {
        var response = postMessage(openCase.code(), subject, JSON.writeValueAsString(Map.of("body", body)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.messageId").isNotEmpty())
                .andExpect(jsonPath("$.senderType").value(senderType))
                .andExpect(jsonPath("$.messageType").value("TEXT"))
                .andExpect(jsonPath("$.body").value(body))
                .andExpect(jsonPath("$.sentAt").isNotEmpty())
                .andExpect(jsonPath("$.offer").value(nullValue()))
                .andReturn().getResponse();
        return JsonPath.read(response.getContentAsString(), "$.messageId");
    }

    private ResultActions postMessage(String caseCode, String subject, String requestBody) throws Exception {
        return mvc.perform(post(MESSAGES, caseCode).principal(() -> subject)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody));
    }

    private long storedSenderId(String messageId) {
        return jdbc.sql("SELECT sender_user_id FROM chat_messages WHERE case_id = :caseId AND message_code = :messageId")
                .param("caseId", openCase.id()).param("messageId", messageId).query(Long.class).single();
    }

    private void setCaseStatus(String status) {
        jdbc.sql("UPDATE cases SET status = :status WHERE id = :caseId")
                .param("status", status).param("caseId", openCase.id()).update();
    }

    private void insertOffer() {
        long jobId = jdbc.sql("""
                INSERT INTO jobs (job_code, booking_id, case_id, kind, trigger_type, parameters)
                VALUES (:code, :booking, :caseId, 'QUOTE_SEARCH', 'TEST', '{}'::jsonb) RETURNING id
                """).param("code", "JOB-" + suffix).param("booking", closedCase.bookingId())
                .param("caseId", closedCase.id()).query(Long.class).single();
        long offerId = jdbc.sql("""
                INSERT INTO offers (offer_code, case_id, status, sent_by, sent_at, idempotency_key, request_hash)
                VALUES (:code, :caseId, 'SUPERSEDED', :counselor, :sentAt, :code, repeat('0', 64)) RETURNING id
                """).param("code", "OFFER-" + suffix).param("caseId", closedCase.id())
                .param("counselor", counselorId).param("sentAt", TIE).query(Long.class).single();
        for (int displayOrder = 3; displayOrder >= 1; displayOrder--) {
            long quoteId = jdbc.sql("""
                    INSERT INTO alternative_quotes (quote_code, search_job_id, hotel_id, room_type_code,
                        rate_plan_code, check_in_date, check_out_date, adults, rooms, base_amount,
                        mandatory_fees_amount, total_amount, distance_meters, available_room_count, max_adults,
                        cancellation_policy, checked_at, expires_at, source_payload, case_id, property_name, room_name)
                    VALUES (:code, :job, :hotel, 'ROOM', 'RATE', DATE '2026-11-10', DATE '2026-11-13',
                        2, 1, 480000, 0, 480000, 800, 1, 2, 'TEST', :checkedAt, :expiresAt, '{}'::jsonb,
                        :caseId, '테스트 후보 숙소', '테스트 객실') RETURNING id
                    """).param("code", quoteCode(displayOrder)).param("job", jobId).param("hotel", hotelId)
                    .param("checkedAt", FIRST).param("expiresAt", FIRST.plusHours(1)).param("caseId", closedCase.id())
                    .query(Long.class).single();
            jdbc.sql("""
                    INSERT INTO offer_items (offer_id, quote_id, case_id, display_order)
                    VALUES (:offer, :quote, :caseId, :displayOrder)
                    """).param("offer", offerId).param("quote", quoteId).param("caseId", closedCase.id())
                    .param("displayOrder", displayOrder).update();
        }
        insertMessage(closedCase.id(), messageCode("OLD"), ownerId, "CUSTOMER", "TEXT", "테스트 메시지", null, FIRST);
        insertMessage(closedCase.id(), messageCode("TIE"), counselorId, "COUNSELOR", "TEXT", "테스트 답변", null, TIE);
        insertMessage(closedCase.id(), messageCode("NEW"), null, "SYSTEM", "SYSTEM", "테스트 안내", null, TIE.plusMinutes(1));
        insertMessage(closedCase.id(), messageCode("OFR"), counselorId, "COUNSELOR", "OFFER", null, offerId, TIE);
    }

    private void insertMessage(long caseId, String code, Long senderId, String senderType, String messageType,
                               String body, Long offerId, OffsetDateTime sentAt) {
        jdbc.sql("""
                INSERT INTO chat_messages (message_code, case_id, sender_user_id, sender_type, message_type, body,
                    offer_id, sent_at)
                VALUES (:code, :caseId, :sender, :senderType, :messageType, :body, :offer, :sentAt)
                """).param("code", code).param("caseId", caseId).param("sender", senderId)
                .param("senderType", senderType).param("messageType", messageType).param("body", body)
                .param("offer", offerId).param("sentAt", sentAt).update();
    }

    private long insertUser(String subject, String role) {
        return jdbc.sql("""
                INSERT INTO users (subject_key, display_name, role)
                VALUES (:subject, '테스트 사용자', :role) RETURNING id
                """).param("subject", subject).param("role", role).query(Long.class).single();
    }

    private TestCase insertCase(long bookingId, String status) {
        String code = newCaseCode();
        long id = jdbc.sql("""
                INSERT INTO cases (case_code, booking_id, status, closed_at, close_reason)
                VALUES (:code, :booking, :status, CASE WHEN :status = 'CLOSED' THEN now() END,
                    CASE WHEN :status = 'CLOSED' THEN 'TEST_RESOLVED' END) RETURNING id
                """).param("code", code).param("booking", bookingId).param("status", status).query(Long.class).single();
        return new TestCase(id, code, bookingId);
    }

    private static String newCaseCode() {
        return "C-" + Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits()) + "000";
    }

    private String messageCode(String label) {
        return "M-" + label + "-" + suffix;
    }

    private String quoteCode(int order) {
        return "Q-" + order + "-" + suffix;
    }

    private String historySnapshot() {
        return jdbc.sql("""
                SELECT jsonb_build_object('case', to_jsonb(c), 'history', (
                    SELECT jsonb_agg(to_jsonb(m) ORDER BY m.id) FROM chat_messages m WHERE m.case_id = c.id
                ))::text FROM cases c WHERE c.id = :caseId
                """).param("caseId", closedCase.id()).query(String.class).single();
    }

    private ResultActions expectProblem(ResultActions action, int statusCode, String code) throws Exception {
        return action.andExpect(status().is(statusCode))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    private record TestCase(long id, String code, long bookingId) {}

    public static final class IsolatedDatabaseLoader extends SpringBootContextLoader {
        private static final String DATABASE = "yeohaengbom_chat_api_test";
        private static final String URL = "jdbc:postgresql://127.0.0.1:25433/" + DATABASE;
        private static final String USER = "yb";

        @Override
        protected SpringApplication getSpringApplication() {
            String url = System.getenv("CHATTING_API_TEST_DB_URL");
            String password = System.getenv("CHATTING_API_TEST_DB_PASSWORD");
            if (!URL.equals(url) || password == null || password.isBlank()) {
                throw new IllegalStateException("Chat API tests require CHATTING_API_TEST_DB_URL=" + URL
                        + " and CHATTING_API_TEST_DB_PASSWORD");
            }
            var dataSource = new DriverManagerDataSource(url, USER, password);
            verifyDatabaseIdentity(dataSource);
            var application = super.getSpringApplication();
            application.addInitializers(context -> {
                context.getBeanFactory().registerSingleton("dataSource", dataSource);
                TestPropertyValues.of(Map.of(
                        "spring.flyway.url", url,
                        "spring.flyway.user", USER,
                        "spring.flyway.password", password,
                        "spring.flyway.enabled", "true",
                        "spring.flyway.locations", "classpath:db/migration",
                        "spring.jpa.hibernate.ddl-auto", "none"))
                        .applyTo(context.getEnvironment());
            });
            return application;
        }

        private static void verifyDatabaseIdentity(DriverManagerDataSource dataSource) {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT current_database(), current_user")) {
                if (!result.next() || !DATABASE.equals(result.getString(1)) || !USER.equals(result.getString(2))) {
                    throw new IllegalStateException("Chat API test database identity did not match");
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to verify the chat API test database before Spring starts");
            }
        }
    }
}
