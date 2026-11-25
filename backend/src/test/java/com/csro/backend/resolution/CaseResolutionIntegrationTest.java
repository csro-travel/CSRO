package com.csro.backend.resolution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.csro.backend.offers.OfferSelectionService;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisClient;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisService;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisStore;
import com.csro.backend.incidents.IncidentRecheckJobs;
import com.csro.backend.incidents.IncidentReportService;
import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.reconfirmation.BookingReconfirmationException;
import com.csro.backend.reconfirmation.ReconfirmationScope;
import com.csro.backend.quotes.QuoteContracts.*;
import com.csro.backend.quotes.QuoteRankingClient;
import com.csro.backend.quotes.QuoteSearchService;
import com.csro.backend.quotes.QuoteSupplierClient;
import com.csro.backend.replacements.ReplacementService;
import com.csro.backend.replacements.ReplacementSupplierClient;
import com.csro.backend.replacements.ReplacementSupplierClient.FrozenRequest;
import com.csro.backend.replacements.ReplacementSupplierClient.Outcome;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.AdditionalAnswers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real V1 and real search/offer/reservation flow; only provider/Agent HTTP calls are doubled. */
@EnabledIfEnvironmentVariable(named="RESOLUTION_TEST", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseResolutionIntegrationTest {
    private static final Principal COUNSELOR=()->"demo-counselor-001";
    private static final Principal CUSTOMER=()->"demo-customer-001";
    private static final Principal OTHER=()->"demo-customer-002";
    private static final OffsetDateTime QUOTE_AT=OffsetDateTime.parse("2026-11-09T10:20:00+09:00");
    private static final OffsetDateTime ARRIVED=OffsetDateTime.parse("2026-11-10T15:00:00+09:00");
    private static final OffsetDateTime CHECKED=QUOTE_AT.minusMinutes(5), EXPIRES=QUOTE_AT.plusMinutes(5);
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private MutableClock clock;
    private CaseResolutionService service;
    private ReplacementService replacements;
    private Fixture fixture;
    private String requestId;

    @BeforeAll
    void verifyConfiguredEndpointAndActualIdentityBeforeFlywayOrSpring() {
        var settings=ResolutionTestDatabase.validate(System.getenv());
        var source=new DriverManagerDataSource(settings.url(),settings.user(),settings.password());
        ResolutionTestDatabase.verifyIdentity(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/local")
                .cleanDisabled(true).load().migrate();
        jdbc=JdbcClient.create(source); manager=new DataSourceTransactionManager(source);
    }

    @BeforeEach
    void useUniqueCasesWithoutDeletingOrResettingExistingRows() {
        clock=new MutableClock(Instant.parse("2026-11-24T12:00:00.123456789Z"));
        service=new CaseResolutionService(jdbc,manager,clock);
        prepareReservation(false,true);
    }

    @Test
    void fullStayCustomerCheckinAndManualFollowupsCloseOnceWithoutChangingOriginalFactsOrBooking() {
        String original=originalSnapshot();
        long messages=count("chat_messages");
        var entered=checkin("arrived");
        assertEquals("CUSTOMER_CONFIRMED",entered.path("status").asString());
        assertEquals(ARRIVED.toInstant(),OffsetDateTime.parse(entered.path("checkinAt").asString()).toInstant());
        assertEquals("DONE",taskStatus("CHECKIN_CONFIRM"));
        assertEquals("TODO",taskStatus("CONTACT_CUSTOMER"));
        assertEquals("TODO",taskStatus("ORIGINAL_FOLLOWUP"));
        assertEquals(1L,count("checkin_confirmations"));
        assertEquals(messages+1,count("chat_messages"));
        finishManualTasks();
        var readiness=resolution(COUNSELOR);
        assertTrue(readiness.path("coverage").path("fullyCovered").booleanValue());
        assertTrue(readiness.path("canClose").booleanValue());
        long before=version();
        var closed=close("full-close",before);
        assertEquals("CLOSED",closed.path("status").asString());
        assertEquals(before+1,closed.path("version").longValue());
        assertEquals(1L,audits("CASE_CLOSED"));
        assertEquals(original,originalSnapshot(),"Resolution cannot change original hotel facts, reservation, or frozen provider payload");
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM checkin_confirmations WHERE case_id=:id AND source_type='CUSTOMER' AND target_type='REPLACEMENT' AND confirmation_status='CUSTOMER_CONFIRMED' AND confirmed_by=(SELECT id FROM users WHERE subject_key='demo-customer-001')")
                .param("id",fixture.caseId()).query(Long.class).single());
    }

    @Test
    void checkinChecksAuthenticationOwnershipRoleAndActualStayDateBeforeWriting() {
        String before=snapshot();
        assertCode(401,"UNAUTHENTICATED",()->service.checkin(null,requestId,"unauth",ARRIVED,true));
        assertCode(403,"FORBIDDEN_ROLE",()->service.checkin(COUNSELOR,requestId,"counselor",ARRIVED,true));
        assertCode(404,"REPLACEMENT_BOOKING_NOT_FOUND",()->service.checkin(OTHER,requestId,"foreign",ARRIVED,true));
        assertCode(409,"CHECKIN_OUTSIDE_STAY",()->service.checkin(CUSTOMER,requestId,"before-stay",QUOTE_AT,true));
        assertCode(409,"CHECKIN_OUTSIDE_STAY",()->service.checkin(CUSTOMER,requestId,"after-stay",OffsetDateTime.parse("2026-11-13T00:00:00+09:00"),true));
        assertEquals(before,snapshot());
    }

    @Test
    void unconfirmedReservationCannotBeTurnedIntoArrivalAndReadinessIsReadOnly() {
        prepareReservation(false,false);
        String before=snapshot();
        assertCode(409,"REPLACEMENT_RESULT_UNRESOLVED",()->service.checkin(CUSTOMER,requestId,"not-confirmed",ARRIVED,true));
        var current=resolution(COUNSELOR);
        assertFalse(current.path("canClose").booleanValue());
        resolution(CUSTOMER);
        assertEquals(before,snapshot());
        assertEquals("PROCESSING",JSON.readTree(replacements.view(COUNSELOR,requestId)).path("status").asString());
    }

    @Test
    void closeWithoutDirectCheckinOrFinishedRequiredTasksMakesNoPartialWrites() {
        String before=snapshot();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"premature",new CaseResolutionService.Closure("아직 입실하지 않음",version()),ARRIVED,true));
        assertCode(403,"FORBIDDEN_ROLE",()->service.close(CUSTOMER,fixture.code(),"customer-close",new CaseResolutionService.Closure("고객 대리 종료",version()),ARRIVED,true));
        assertEquals(before,snapshot());
        assertEquals("OPEN",caseStatus());
    }

    @Test
    void partialReplacementCannotHideTheRemainingUnknownNights() {
        prepareReservation(true,true);
        checkin("partial-arrived"); finishManualTasks();
        var current=resolution(COUNSELOR);
        assertFalse(current.path("coverage").path("fullyCovered").booleanValue());
        assertEquals(List.of("2026-11-11","2026-11-12"),dates(current.path("coverage").path("uncoveredStayDates")));
        String before=snapshot();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"partial-close",new CaseResolutionService.Closure("부분만 해결",version()),ARRIVED,true));
        assertEquals(before,snapshot());
    }

    @Test
    void partialReplacementCanCloseOnlyAfterRemainingOriginalNightsHaveAcceptedAvailableEvidence() {
        prepareReservation(true,true);
        checkin("partial-arrived"); finishManualTasks();
        verifyNight(LocalDate.of(2026,11,11),"AVAILABLE");
        verifyNight(LocalDate.of(2026,11,12),"AVAILABLE");
        jdbc.sql("UPDATE evidence_nights SET review_status='REJECTED' WHERE id=(SELECT accepted_assertion_id FROM booking_nights WHERE booking_id=:id AND stay_date=DATE '2026-11-11')")
                .param("id",fixture.bookingId()).update();
        assertFalse(resolution(COUNSELOR).path("canClose").booleanValue(),"A stale VERIFIED flag cannot replace an accepted AVAILABLE assertion");
        jdbc.sql("UPDATE evidence_nights SET review_status='ACCEPTED' WHERE id=(SELECT accepted_assertion_id FROM booking_nights WHERE booking_id=:id AND stay_date=DATE '2026-11-11')")
                .param("id",fixture.bookingId()).update();
        var current=resolution(COUNSELOR);
        assertTrue(current.path("coverage").path("fullyCovered").booleanValue());
        assertTrue(current.path("canClose").booleanValue());
        String original=originalSnapshot();
        assertEquals("CLOSED",close("partial-covered-close",version()).path("status").asString());
        assertEquals(original,originalSnapshot());
        assertEquals("UNAVAILABLE",jdbc.sql("SELECT fulfillment_status FROM booking_nights WHERE booking_id=:id AND stay_date=DATE '2026-11-10'")
                .param("id",fixture.bookingId()).query(String.class).single());
    }

    @Test
    void changedQuoteScopeCannotExtendOrReplaceTheConfirmedReservationCoverage() {
        checkin("arrived");finishManualTasks();
        String roomType=jdbc.sql("SELECT q.room_type_code FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id JOIN alternative_quotes q ON q.id=s.quote_id WHERE r.request_code=:code")
                .param("code",requestId).query(String.class).single();
        jdbc.sql("UPDATE alternative_quotes SET room_type_code='DIFFERENT-ROOM' WHERE id=(SELECT s.quote_id FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id WHERE r.request_code=:code)")
                .param("code",requestId).update();
        assertTrue(resolution(COUNSELOR).path("blockers").toString().contains("REPLACEMENT_SCOPE_INVALID"),"A different room cannot inherit the original supplier booking coverage");
        jdbc.sql("UPDATE alternative_quotes SET room_type_code=:room WHERE id=(SELECT s.quote_id FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id WHERE r.request_code=:code)")
                .param("room",roomType).param("code",requestId).update();
        assertTrue(resolution(COUNSELOR).path("canClose").booleanValue());
        jdbc.sql("UPDATE alternative_quotes SET check_out_date=DATE '2026-11-12' WHERE id=(SELECT s.quote_id FROM replacement_requests r JOIN alternative_selections s ON s.id=r.selection_id WHERE r.request_code=:code)")
                .param("code",requestId).update();
        var current=resolution(COUNSELOR);
        assertFalse(current.path("canClose").booleanValue());
        assertTrue(current.path("blockers").toString().contains("REPLACEMENT_SCOPE_INVALID"));
        String before=snapshot();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"changed-scope",new CaseResolutionService.Closure("변경된 견적 기간",version()),ARRIVED,true));
        assertEquals(before,snapshot());
    }

    @Test
    void anUnresolvedHistoricalReservationBlocksArrivalAndClosureEvenWhenAnotherBookingIsConfirmed() {
        String unknown="BRQ-"+UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        String selectionCode="SEL-"+UUID.randomUUID();
        long historical=jdbc.sql("""
                INSERT INTO alternative_selections (selection_code,case_id,offer_id,quote_id,selected_by,selected_at,superseded_at,idempotency_key,request_hash)
                SELECT :code,s.case_id,s.offer_id,s.quote_id,s.selected_by,s.selected_at,s.selected_at,:key,s.request_hash
                FROM alternative_selections s JOIN replacement_requests r ON r.selection_id=s.id WHERE r.request_code=:request RETURNING id
                """).param("code",selectionCode).param("key","legacy-"+UUID.randomUUID()).param("request",requestId).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO replacement_requests (request_code,selection_id,idempotency_key,request_hash,status,expected_amount,currency,request_payload,created_at,updated_at,case_id,requested_by)
                SELECT :code,:selection,:code,r.request_hash,'RESULT_UNKNOWN',r.expected_amount,r.currency,r.request_payload,r.created_at,r.updated_at,r.case_id,r.requested_by
                FROM replacement_requests r WHERE r.request_code=:request
                """).param("code",unknown).param("selection",historical).param("request",requestId).update();
        String before=snapshot();
        assertCode(409,"REPLACEMENT_RESULT_UNRESOLVED",()->service.checkin(CUSTOMER,requestId,"unknown-history",ARRIVED,true));
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"unknown-history-close",new CaseResolutionService.Closure("미확정 요청 존재",version()),ARRIVED,true));
        assertTrue(resolution(COUNSELOR).path("blockers").toString().contains("REPLACEMENT_RESULT_UNRESOLVED"));
        assertEquals(before,snapshot());
    }

    @Test
    void missingMandatoryTaskAndExtraUnfinishedRequiredWorkBothPreventClosure() {
        checkin("arrived"); finishManualTasks();
        jdbc.sql("UPDATE case_tasks SET is_required=false WHERE case_id=:id AND task_type='ORIGINAL_FOLLOWUP'").param("id",fixture.caseId()).update();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"missing-followup",new CaseResolutionService.Closure("필수 후속 업무 누락",version()),ARRIVED,true));
        jdbc.sql("UPDATE case_tasks SET is_required=true WHERE case_id=:id AND task_type='ORIGINAL_FOLLOWUP'").param("id",fixture.caseId()).update();
        long task=jdbc.sql("INSERT INTO case_tasks (case_id,booking_id,task_type,status,is_required) VALUES (:id,:booking,'CUSTOMER_CALLBACK','TODO',true) RETURNING id")
                .param("id",fixture.caseId()).param("booking",fixture.bookingId()).query(Long.class).single();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"unfinished-extra",new CaseResolutionService.Closure("필수 회신 미완료",version()),ARRIVED,true));
        service.completeTask(COUNSELOR,fixture.code(),"TASK-"+task,"callback",new CaseResolutionService.TaskCompletion("DONE","고객 회신을 확인함",version(),null));
        assertEquals("CLOSED",close("all-required-finished",version()).path("status").asString());
    }

    @Test
    void hotelAnalysisRequiredReviewMustBeCompletedBeforeAnOtherwiseResolvedCaseCanClose() {
        checkin("analysis-arrived"); finishManualTasks();
        assertTrue(resolution(COUNSELOR).path("canClose").booleanValue());
        long evidence=jdbc.sql("""
                INSERT INTO evidence_records(booking_id,source_type,source_authenticated,raw_text,occurred_at,received_at)
                VALUES (:booking,'MANUAL_HOTEL',false,'첫날은 확인 중입니다.',:occurred,:received) RETURNING id
                """).param("booking",fixture.bookingId()).param("occurred",QUOTE_AT).param("received",now())
                .query(Long.class).single();
        var agent=mock(HotelReplyAnalysisClient.class);
        doAnswer(call -> {
            HotelReplyAnalysisContracts.Input input=call.getArgument(0);
            String response=JSON.writeValueAsString(Map.of("evidenceId",input.evidenceId(),"analysisStatus","AMBIGUOUS",
                    "modelVersion","hotel-reply-extractor-0.1","claims",List.of(),
                    "unresolvedStayDates",input.stayDates().stream().map(LocalDate::toString).toList(),
                    "requiresHumanVerification",true,"warnings",List.of("사람의 확인이 필요합니다.")));
            return new HotelReplyAnalysisContracts.Analysis(input.evidenceId(),"AMBIGUOUS","hotel-reply-extractor-0.1",
                    List.of(),input.stayDates(),true,List.of("사람의 확인이 필요합니다."),
                    HotelReplyAnalysisClient.requestJson(input),response);
        }).when(agent).analyze(any());
        var analysis=new HotelReplyAnalysisService(agent,new HotelReplyAnalysisStore(jdbc,manager,clock)).analyze(evidence);
        assertNotNull(analysis.manualTaskId());
        assertEquals("TODO",taskStatus("VERIFY_HOTEL"));
        String before=snapshot();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"pending-hotel-review",
                new CaseResolutionService.Closure("분석의 사람 확인이 남음",version()),ARRIVED,true));
        assertEquals(before,snapshot());
        service.completeTask(COUNSELOR,fixture.code(),"TASK-"+analysis.manualTaskId(),"hotel-reviewed",
                new CaseResolutionService.TaskCompletion("DONE","저장 원문을 수동 확인함. 분석만으로 원호텔 사실을 변경하지 않음",version(),null));
        assertEquals("CLOSED",close("after-hotel-review",version()).path("status").asString());
    }

    @Test
    void failureAfterCaseStatusUpdateRollsBackClosureAuditVersionAndSystemMessage() {
        checkin("rollback-arrived"); finishManualTasks();
        String before=snapshot();
        JdbcClient failing=mock(JdbcClient.class,AdditionalAnswers.delegatesTo(jdbc));
        doThrow(new DataAccessResourceFailureException("controlled private database failure"))
                .when(failing).sql(org.mockito.ArgumentMatchers.argThat((String sql)->sql.startsWith("INSERT INTO case_actions")));
        var guarded=new CaseResolutionService(failing,manager,clock);
        assertThrows(DataAccessResourceFailureException.class,()->guarded.close(COUNSELOR,fixture.code(),"rollback-close",
                new CaseResolutionService.Closure("모두 확인",version()),ARRIVED,true));
        assertEquals(before,snapshot());
        assertEquals("OPEN",caseStatus());
        assertEquals(0L,audits("CASE_CLOSED"));
    }

    @ParameterizedTest
    @ValueSource(strings={"PENDING","RUNNING","WAITING"})
    void anyActiveBookingRecheckBlocksClosureWithoutChangingForeignWork(String state) {
        checkin("recheck-arrived"); finishManualTasks();
        String job="FOREIGN-CLOSE-"+UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO jobs(job_code,booking_id,kind,trigger_type,status,parameters)
                VALUES (:code,:booking,'RECHECK','OTHER_OWNER',:state,'{}'::jsonb)
                """).param("code",job).param("booking",fixture.bookingId()).param("state",state).update();
        assertTrue(resolution(COUNSELOR).path("blockers").toString().contains("RECHECK_PENDING"));
        String before=snapshot();
        assertCode(409,"CASE_NOT_RESOLVED",()->service.close(COUNSELOR,fixture.code(),"active-recheck-close",
                new CaseResolutionService.Closure("재확인 작업이 남아 있음",version()),ARRIVED,true));
        assertEquals(before,snapshot());
        jdbc.sql("UPDATE jobs SET status='COMPLETED',finished_at=:at WHERE job_code=:code")
                .param("at",now()).param("code",job).update();
        assertEquals("CLOSED",close("terminal-recheck-close",version()).path("status").asString());
    }

    @Test
    void realIncidentReceiptSurvivesClosureAndOnlyANewReportCreatesAnotherActiveCase() {
        var evidence=mock(BookingReconfirmationEvidenceService.class);
        var failure=mock(BookingReconfirmationException.class);
        doReturn(BookingReconfirmationException.Step.API_027).when(failure).step();
        doReturn(BookingReconfirmationException.Kind.NOT_FOUND).when(failure).kind();
        doReturn(404).when(failure).httpStatus();
        doThrow(failure).when(evidence).reconfirmAndApply(anyString(),anyString(),any(ReconfirmationScope.class),anyLong(),any());
        var worker=new IncidentRecheckJobs(evidence,jdbc,manager,clock,IncidentRecheckJobs.RetryPolicy.defaults());
        var incidents=new IncidentReportService(jdbc,manager,worker,clock);
        String booking=jdbc.sql("SELECT booking_code FROM bookings WHERE id=:id").param("id",fixture.bookingId()).query(String.class).single();
        String original=originalSnapshot();
        var first=incidents.submit(CUSTOMER,booking,"onsite-report","CHECKIN_FAILED",true,"원숙소에서 현장 확인을 요청합니다.");
        assertEquals(fixture.code(),first.incidentCase().caseId());
        String job=jdbc.sql("SELECT job_code FROM jobs WHERE booking_id=:booking AND kind='RECHECK'")
                .param("booking",fixture.bookingId()).query(String.class).single();
        // A worker tick follows enqueue; PostgreSQL rounds the nanosecond test clock to microseconds.
        clock.advance(1);
        assertEquals(IncidentRecheckJobs.State.FAILED,worker.execute(job).state());
        verify(evidence).reconfirmAndApply(anyString(),anyString(),any(ReconfirmationScope.class),anyLong(),any());
        checkin("incident-arrived"); finishManualTasks();
        service.completeTask(COUNSELOR,fixture.code(),task("VERIFY_HOTEL"),"incident-verified",
                new CaseResolutionService.TaskCompletion("DONE","재확인 실패를 검토하고 대체 숙박 보장과 고객 입실을 확인함",version(),null));
        var closeBody=new CaseResolutionService.Closure("신고 및 입실·후속 확인 완료",version());
        String closed=service.close(COUNSELOR,fixture.code(),"incident-close",closeBody,ARRIVED,true);
        String afterClose=snapshot();
        assertEquals(first,incidents.submit(CUSTOMER,booking,"onsite-report","CHECKIN_FAILED",true,"원숙소에서 현장 확인을 요청합니다."));
        assertEquals(closed,service.close(COUNSELOR,fixture.code(),"incident-close",closeBody,ARRIVED.plusMonths(1),true));
        assertEquals(afterClose,snapshot());
        var next=incidents.submit(CUSTOMER,booking,"new-onsite-report","CHECKIN_FAILED",false,"추가 현장 문제가 발생했습니다.");
        assertTrue(next.incidentCase().created());
        assertNotEquals(first.incidentCase().caseId(),next.incidentCase().caseId());
        assertEquals("OPEN",next.incidentCase().status());
        assertEquals("CLOSED",caseStatus());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM cases WHERE booking_id=:booking AND status IN ('OPEN','IN_PROGRESS','WAITING')")
                .param("booking",fixture.bookingId()).query(Long.class).single());
        String afterNewReport=snapshot();
        assertEquals(first,incidents.submit(CUSTOMER,booking,"onsite-report","CHECKIN_FAILED",true,"원숙소에서 현장 확인을 요청합니다."));
        assertEquals(closed,service.close(COUNSELOR,fixture.code(),"incident-close",closeBody,ARRIVED.plusMonths(1),true));
        assertEquals(afterNewReport,snapshot());
        assertEquals(2L,jdbc.sql("SELECT count(*) FROM incident_reports WHERE booking_id=:booking")
                .param("booking",fixture.bookingId()).query(Long.class).single());
        assertEquals(original,originalSnapshot());
    }

    @Test
    void manualCheckinBypassAndRequestedFollowupDoNotCountAsCompletedWork() {
        String before=snapshot();
        assertCode(400,"INVALID_TASK_ID",()->service.completeTask(COUNSELOR,fixture.code(),"TASK-0"+task("CONTACT_CUSTOMER").substring(5),"noncanonical-task",
                new CaseResolutionService.TaskCompletion("DONE","비정규 업무 번호",version(),null)));
        assertCode(409,"CHECKIN_REQUIRES_CONFIRMATION",()->service.completeTask(COUNSELOR,fixture.code(),task("CHECKIN_CONFIRM"),"fake-checkin-task",
                new CaseResolutionService.TaskCompletion("DONE","상담사가 대신 입실 처리",version(),null)));
        assertCode(400,"INVALID_TASK_COMPLETION",()->service.completeTask(COUNSELOR,fixture.code(),task("ORIGINAL_FOLLOWUP"),"requested-only",
                new CaseResolutionService.TaskCompletion("DONE","취소를 요청만 함",version(),"REQUESTED")));
        assertEquals(before,snapshot());
        assertEquals("TODO",taskStatus("CHECKIN_CONFIRM")); assertEquals("TODO",taskStatus("ORIGINAL_FOLLOWUP"));
    }

    @Test
    void staleVersionsAndReusedTaskKeyCannotOverwriteNewerFactsOrAnotherTask() {
        long oldVersion=version(); checkin("version-arrived");
        String before=snapshot();
        assertCode(409,"CASE_VERSION_CONFLICT",()->service.completeTask(COUNSELOR,fixture.code(),task("CONTACT_CUSTOMER"),"old-version",
                new CaseResolutionService.TaskCompletion("DONE","이전 화면에서 안내 완료",oldVersion,null)));
        assertEquals(before,snapshot());
        var body=new CaseResolutionService.TaskCompletion("DONE","예약 안내를 마침",version(),null);
        service.completeTask(COUNSELOR,fixture.code(),task("CONTACT_CUSTOMER"),"task-key",body);
        assertCode(409,"IDEMPOTENCY_KEY_REUSED",()->service.completeTask(COUNSELOR,fixture.code(),task("ORIGINAL_FOLLOWUP"),"task-key",
                new CaseResolutionService.TaskCompletion("DONE","후속 완료",version(),"COMPLETED")));
        assertEquals("TODO",taskStatus("ORIGINAL_FOLLOWUP"));
    }

    @Test
    void immutableReceiptsReplayAfterClosureAndLaterTimeWithoutChangingVersionOrHistory() {
        var checkin=checkin("replay-checkin");
        var contactBody=new CaseResolutionService.TaskCompletion("DONE","고객에게 안내 완료",version(),null);
        var contact=JSON.readTree(service.completeTask(COUNSELOR,fixture.code(),task("CONTACT_CUSTOMER"),"replay-contact",contactBody));
        service.completeTask(COUNSELOR,fixture.code(),task("ORIGINAL_FOLLOWUP"),"followup",
                new CaseResolutionService.TaskCompletion("DONE","시연 후속 처리 확인. 실제 취소 실행 아님",version(),"COMPLETED"));
        var closeBody=new CaseResolutionService.Closure("입실과 필수 업무를 확인함",version());
        var closed=JSON.readTree(service.close(COUNSELOR,fixture.code(),"replay-close",closeBody,ARRIVED,true));
        String before=snapshot(); clock.advance(86400);
        assertEquals(checkin,JSON.readTree(service.checkin(CUSTOMER,requestId,"replay-checkin",ARRIVED.plusMonths(1),true)));
        assertEquals(contact,JSON.readTree(service.completeTask(COUNSELOR,fixture.code(),task("CONTACT_CUSTOMER"),"replay-contact",contactBody)));
        assertEquals(closed,JSON.readTree(service.close(COUNSELOR,fixture.code(),"replay-close",closeBody,ARRIVED.plusMonths(1),true)));
        assertCode(409,"IDEMPOTENCY_KEY_REUSED",()->service.close(COUNSELOR,fixture.code(),"replay-close",new CaseResolutionService.Closure("다른 종료 사유",closeBody.expectedVersion()),ARRIVED,true));
        assertEquals(before,snapshot());
    }

    @Test
    void simultaneousIdenticalCheckinAndCloseRequestsEachCommitOneEventAndOneVersionChange() throws Exception {
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{start.await();return checkin("concurrent-checkin");});
            var second=pool.submit(()->{start.await();return checkin("concurrent-checkin");});
            start.countDown(); assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1L,count("checkin_confirmations")); assertEquals(1L,version());
        finishManualTasks(); long before=version(); var body=new CaseResolutionService.Closure("동시 종료",before);
        var closeStart=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{closeStart.await();return JSON.readTree(service.close(COUNSELOR,fixture.code(),"concurrent-close",body,ARRIVED,true));});
            var second=pool.submit(()->{closeStart.await();return JSON.readTree(service.close(COUNSELOR,fixture.code(),"concurrent-close",body,ARRIVED,true));});
            closeStart.countDown();assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(before+1,version()); assertEquals(1L,audits("CASE_CLOSED"));
    }

    @Test
    void customerSummaryNeverExposesCounselorNotesClosureReasonOrInternalTaskDetails() {
        checkin("customer-summary");
        String privateNote="상담사 전용 정산 검토 메모 "+UUID.randomUUID();
        service.completeTask(COUNSELOR,fixture.code(),task("CONTACT_CUSTOMER"),"private-contact",
                new CaseResolutionService.TaskCompletion("DONE",privateNote,version(),null));
        service.completeTask(COUNSELOR,fixture.code(),task("ORIGINAL_FOLLOWUP"),"private-followup",
                new CaseResolutionService.TaskCompletion("DONE","시연용 후속 처리 확인",version(),"NOT_REQUIRED"));
        close("summary-close",version());
        String before=snapshot(); var customer=resolution(CUSTOMER);
        assertEquals("CLOSED",customer.path("caseStatus").asString());
        for(String field:List.of("coverage","requiredTasks","blockers","canClose"))assertFalse(customer.has(field));
        assertFalse(customer.path("closure").has("reason")); assertFalse(customer.path("closure").has("version"));
        assertFalse(customer.toString().contains(privateNote));
        assertCode(404,"CASE_NOT_FOUND",()->service.resolution(OTHER,fixture.code(),ARRIVED,true));
        assertEquals(before,snapshot());
    }

    private JsonNode checkin(String key) {return JSON.readTree(service.checkin(CUSTOMER,requestId,key,ARRIVED,true));}
    private JsonNode close(String key,long expected) {return JSON.readTree(service.close(COUNSELOR,fixture.code(),key,new CaseResolutionService.Closure("입실·보장·시연 후속 업무를 확인함",expected),ARRIVED,true));}
    private JsonNode resolution(Principal actor) {return JSON.readTree(service.resolution(actor,fixture.code(),ARRIVED,true));}
    private void finishManualTasks() {
        service.completeTask(COUNSELOR,fixture.code(),task("CONTACT_CUSTOMER"),"contact",new CaseResolutionService.TaskCompletion("DONE","고객에게 예약번호를 안내함",version(),null));
        service.completeTask(COUNSELOR,fixture.code(),task("ORIGINAL_FOLLOWUP"),"original-followup",new CaseResolutionService.TaskCompletion("DONE","가상/수동 후속 처리 확인. 실제 공급사 취소·환불 실행 아님",version(),"COMPLETED"));
    }
    private String task(String type) {return "TASK-"+jdbc.sql("SELECT id FROM case_tasks WHERE case_id=:id AND task_type=:type ORDER BY id DESC LIMIT 1").param("id",fixture.caseId()).param("type",type).query(Long.class).single();}
    private String taskStatus(String type) {return jdbc.sql("SELECT status FROM case_tasks WHERE case_id=:id AND task_type=:type ORDER BY id DESC LIMIT 1").param("id",fixture.caseId()).param("type",type).query(String.class).single();}
    private long version() {return jdbc.sql("SELECT version FROM cases WHERE id=:id").param("id",fixture.caseId()).query(Long.class).single();}
    private String caseStatus() {return jdbc.sql("SELECT status FROM cases WHERE id=:id").param("id",fixture.caseId()).query(String.class).single();}
    private long count(String table) {return jdbc.sql("SELECT count(*) FROM "+table+" WHERE case_id=:id").param("id",fixture.caseId()).query(Long.class).single();}
    private long audits(String type) {return jdbc.sql("SELECT count(*) FROM case_actions WHERE case_id=:id AND action_type=:type").param("id",fixture.caseId()).param("type",type).query(Long.class).single();}
    private static List<String> dates(JsonNode array) {var result=new ArrayList<String>();for(JsonNode item:array)result.add(item.asString());return result;}
    private static void assertCode(int status,String code,Runnable operation) {var ex=assertThrows(ResponseStatusException.class,operation::run);assertEquals(status,ex.getStatusCode().value());assertEquals(code,ex.getReason());}
    private OffsetDateTime now() {return clock.instant().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);}

    private void prepareReservation(boolean partial,boolean confirm) {
        fixture=createFixture();
        if(partial)verifyNight(LocalDate.of(2026,11,10),"UNAVAILABLE");
        var provider=mock(QuoteSupplierClient.class);var ranker=mock(QuoteRankingClient.class);
        doAnswer(call->{SearchInput input=call.getArgument(0);return quoteReply(input,partial&&input.stay().checkOutDate().equals(LocalDate.of(2026,11,13)));}).when(provider).search(any());
        doThrow(new IllegalStateException("simulated unavailable ranking")).when(ranker).rank(any());
        var searches=new QuoteSearchService(provider,ranker,jdbc,manager,clock);
        var found=searches.request(COUNSELOR,fixture.code(),new QuoteSearchService.SearchOptions(3000,300000L),"search",QUOTE_AT,true);
        assertEquals("COMPLETED",searches.execute(found.searchJobId()).status());
        var quotes=jdbc.sql("SELECT quote_code FROM alternative_quotes WHERE case_id=:id ORDER BY id").param("id",fixture.caseId()).query(String.class).list();
        var offers=new OfferSelectionService(jdbc,manager,clock);
        var offered=offers.send(COUNSELOR,fixture.code(),quotes,"offer",QUOTE_AT,true);
        var selection=offers.select(CUSTOMER,fixture.code(),offered.offerId(),quotes.getFirst(),"choice",QUOTE_AT,true);
        var supplier=mock(ReplacementSupplierClient.class);
        doAnswer(call->confirmed(call.getArgument(0))).when(supplier).create(any());
        replacements=new ReplacementService(jdbc,manager,supplier,clock);
        requestId=replacements.request(COUNSELOR,fixture.code(),selection.selectionId(),"booking",QUOTE_AT,true).replacementBookingId();
        if(confirm)assertEquals("CONFIRMED",replacements.execute(requestId).status());
    }

    private Fixture createFixture() {
        String suffix=UUID.randomUUID().toString().substring(0,12);
        long booking=jdbc.sql("""
                INSERT INTO bookings (booking_code,customer_id,hotel_id,supplier_booking_id,check_in_date,check_out_date,adults,rooms,original_total_amount,created_at,updated_at)
                SELECT :code,u.id,h.id,:supplier,DATE '2026-11-10',DATE '2026-11-13',2,1,450000,:at,:at
                FROM users u CROSS JOIN hotels h WHERE u.subject_key='demo-customer-001' AND h.hotel_code='HOTEL-001' RETURNING id
                """).param("code","YB-C-"+suffix).param("supplier","SUP-C-"+suffix).param("at",QUOTE_AT.minusHours(1)).query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights (booking_id,stay_date) SELECT :id,DATE '2026-11-10'+n FROM generate_series(0,2)n").param("id",booking).update();
        Fixture created;
        while(true) {
            var candidate=jdbc.sql("""
                    WITH candidate AS (SELECT nextval(pg_get_serial_sequence('cases','id')) AS id)
                    INSERT INTO cases(id,case_code,booking_id,status,priority)
                    SELECT id,'C-'||lpad(id::text,greatest(3,length(id::text)),'0'),:booking,'OPEN','URGENT'
                    FROM candidate ON CONFLICT DO NOTHING RETURNING id,case_code
                    """).param("booking",booking).query((rs,row)->new Fixture(booking,rs.getLong("id"),rs.getString("case_code"))).optional();
            if(candidate.isPresent()){created=candidate.orElseThrow();break;}
        }
        long id=created.caseId();
        jdbc.sql("INSERT INTO case_tasks (case_id,booking_id,task_type,status,is_required) VALUES (:id,:booking,'CONTACT_CUSTOMER','TODO',true)").param("id",id).param("booking",booking).update();
        return created;
    }

    private void verifyNight(LocalDate date,String status) {
        long night=jdbc.sql("SELECT id FROM booking_nights WHERE booking_id=:id AND stay_date=:date").param("id",fixture.bookingId()).param("date",date).query(Long.class).single();
        long evidence=jdbc.sql("INSERT INTO evidence_records (booking_id,source_type,source_authenticated,raw_payload,received_at) VALUES (:id,'HOTEL_API',true,CAST(:raw AS jsonb),:at) RETURNING id")
                .param("id",fixture.bookingId()).param("raw",JSON.writeValueAsString(Map.of("stayDate",date.toString(),"status",status))).param("at",QUOTE_AT).query(Long.class).single();
        long assertion=jdbc.sql("INSERT INTO evidence_nights (booking_id,evidence_id,booking_night_id,reported_status,review_status,reviewed_at) VALUES (:booking,:evidence,:night,:status,'ACCEPTED',:at) RETURNING id")
                .param("booking",fixture.bookingId()).param("evidence",evidence).param("night",night).param("status",status).param("at",QUOTE_AT).query(Long.class).single();
        jdbc.sql("UPDATE booking_nights SET fulfillment_status=:status,fact_status='VERIFIED',accepted_assertion_id=:assertion WHERE id=:night")
                .param("status",status).param("assertion",assertion).param("night",night).update();
    }

    private String snapshot() {return jdbc.sql("""
            SELECT jsonb_build_object('case',(SELECT to_jsonb(c) FROM cases c WHERE id=:id),
            'requests',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM replacement_requests x WHERE case_id=:id),
            'checkins',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM checkin_confirmations x WHERE case_id=:id),
            'tasks',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM case_tasks x WHERE case_id=:id),
            'actions',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM case_actions x WHERE case_id=:id),
            'rechecks',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM jobs x WHERE booking_id=:booking AND kind='RECHECK'),
            'messages',(SELECT jsonb_agg(to_jsonb(x) ORDER BY id) FROM chat_messages x WHERE case_id=:id))::text
            """).param("id",fixture.caseId()).param("booking",fixture.bookingId()).query(String.class).single();}
    private String originalSnapshot() {return jdbc.sql("""
            SELECT jsonb_build_object('booking',(SELECT to_jsonb(b) FROM bookings b WHERE id=:booking),
            'nights',(SELECT jsonb_agg(to_jsonb(n) ORDER BY id) FROM booking_nights n WHERE booking_id=:booking),
            'requests',(SELECT jsonb_agg(to_jsonb(r) ORDER BY id) FROM replacement_requests r WHERE case_id=:id),
            'quotes',(SELECT jsonb_agg(to_jsonb(q) ORDER BY id) FROM alternative_quotes q WHERE case_id=:id))::text
            """).param("id",fixture.caseId()).param("booking",fixture.bookingId()).query(String.class).single();}
    private Outcome confirmed(FrozenRequest request) {return new Outcome("CONFIRMED","ALT-"+request.requestId().substring(4),request.expectedAmount(),now(),null,null,null,
            JSON.writeValueAsString(Map.of("requestId",request.requestId(),"status","CONFIRMED","quoteId",request.quoteId(),"propertyId",request.propertyId(),
                "supplierBookingId","ALT-"+request.requestId().substring(4),"confirmedPrice",Map.of("totalAmount",request.expectedAmount(),"currency","KRW"),"confirmedAt",now().toString())));}
    private record Fixture(long bookingId,long caseId,String code) {}
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> at;MutableClock(Instant initial){at=new AtomicReference<>(initial);}void advance(long seconds){at.updateAndGet(t->t.plusSeconds(seconds));}
        @Override public Instant instant(){return at.get();}@Override public ZoneId getZone(){return ZoneOffset.UTC;}@Override public Clock withZone(ZoneId ignored){return this;}
    }
    private static SearchResult quoteReply(SearchInput input,boolean empty) {
        List<Map<String,Object>> rows=new ArrayList<>();List<Quote> quotes=new ArrayList<>();
        long nights=ChronoUnit.DAYS.between(input.stay().checkInDate(),input.stay().checkOutDate());
        for(int i=0;!empty&&i<3;i++) {
            String letter="ABC".substring(i,i+1),property="HOTEL-C-"+letter,id="QUOTE-"+letter+"-"+input.searchRequestId();
            long base=(150000L+i*1000L)*nights,fees=10000L*nights,total=base+fees;
            Map<String,Object> row=new LinkedHashMap<>();row.put("quoteId",id);row.put("source","SIMULATED_SUPPLIER");row.put("propertyId",property);row.put("propertyName","가상 호텔 "+letter);
            row.put("distanceMeters",500+i*300);row.put("roomTypeId","ROOM-"+letter);row.put("roomName","가상 객실 "+letter);row.put("ratePlanId","RATE-"+letter);
            row.put("maxAdults",2);row.put("breakfastIncluded",false);row.put("availableRoomCount",2);row.put("price",Map.of("baseAmount",base,"mandatoryFeesAmount",fees,"totalAmount",total,"currency","KRW"));
            row.put("cancellationPolicy","NON_REFUNDABLE");row.put("checkedAt",CHECKED.toString());row.put("expiresAt",EXPIRES.toString());rows.add(row);
            quotes.add(new Quote(id,"SIMULATED_SUPPLIER",property,"가상 호텔 "+letter,500+i*300,"ROOM-"+letter,"가상 객실 "+letter,"RATE-"+letter,2,false,2,base,fees,total,"KRW","NON_REFUNDABLE",CHECKED,EXPIRES,JSON.writeValueAsString(row)));
        }
        var stay=Map.of("checkInDate",input.stay().checkInDate().toString(),"checkOutDate",input.stay().checkOutDate().toString(),"adults",input.stay().adults(),"rooms",input.stay().rooms());
        var response=Map.of("searchRequestId",input.searchRequestId(),"searchStatus","COMPLETED","stay",stay,"checkedAt",CHECKED.toString(),"currency","KRW","quotes",rows);
        var request=Map.of("searchRequestId",input.searchRequestId(),"originPropertyId",input.originPropertyId(),"stay",stay,"filters",Map.of("radiusMeters",input.radiusMeters(),"currency","KRW","maxAverageNightlyAmount",300000));
        return new SearchResult(input.searchRequestId(),input.stay(),CHECKED,"KRW",quotes,JSON.writeValueAsString(request),JSON.writeValueAsString(response));
    }
}
