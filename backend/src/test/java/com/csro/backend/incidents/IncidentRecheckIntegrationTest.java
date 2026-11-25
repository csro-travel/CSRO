package com.csro.backend.incidents;

import static org.junit.jupiter.api.Assertions.*;
import com.csro.backend.reconfirmation.IncidentEvidenceTestFactory;
import com.csro.backend.risk.RiskRecheckJobs;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Real private HTTP + exact isolated PostgreSQL; never resets shared data or calls a live provider. */
@EnabledIfEnvironmentVariable(named="INCIDENT_REPORT_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncidentRecheckIntegrationTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final LocalDate TARGET=LocalDate.parse("2026-11-10");
    private JdbcClient jdbc;
    private PlatformTransactionManager manager;
    private MutableClock clock;
    private HttpServer provider;
    private ExecutorService providerThreads;
    private IncidentRecheckJobs jobs;
    private IncidentReportService reports;
    private Fixture fixture;
    private String jobCode;
    private AtomicInteger supplierCalls;
    private List<String> requestIds;
    private volatile int supplierStatus;
    private volatile int hotelStatus;
    private volatile Runnable onSupplier;
    private volatile Runnable onHotel;
    private AtomicBoolean bookingUnlocked;

    @BeforeAll void guardedDatabaseBeforeFlyway() {
        var settings=IncidentTestDatabase.validate(System.getenv());
        var source=new DriverManagerDataSource(settings.url(),settings.user(),settings.password());
        jdbc=JdbcClient.create(source);
        assertEquals(IncidentTestDatabase.DATABASE,jdbc.sql("SELECT current_database()").query(String.class).single());
        assertEquals(IncidentTestDatabase.USER,jdbc.sql("SELECT current_user").query(String.class).single());
        Flyway.configure().dataSource(source).locations("classpath:db/migration","classpath:db/local")
                .cleanDisabled(true).load().migrate();
        manager=new DataSourceTransactionManager(source);
    }

    @BeforeEach void freshFixtureAndPrivateProvider() throws IOException {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        long hotel=jdbc.sql("INSERT INTO hotels(hotel_code,name,data_source) VALUES(:code,:code,'SIMULATED') RETURNING id")
                .param("code","IW-H-"+suffix).query(Long.class).single();
        String bookingCode="IW-B-"+suffix;
        String supplierId="IW-S-"+suffix;
        long booking=jdbc.sql("""
                INSERT INTO bookings(booking_code,customer_id,hotel_id,supplier_code,supplier_booking_id,
                    supplier_status,check_in_date,check_out_date,adults,rooms,original_total_amount)
                SELECT :code,customer_id,:hotel,'MOCK_SUPPLIER',:supplier,'CONFIRMED',:target,:checkout,2,1,450000
                FROM bookings WHERE booking_code='YB-001' RETURNING id
                """).param("code",bookingCode).param("hotel",hotel).param("supplier",supplierId)
                .param("target",TARGET).param("checkout",TARGET.plusDays(3)).query(Long.class).single();
        jdbc.sql("INSERT INTO booking_nights(booking_id,stay_date) SELECT :id,:target+n FROM generate_series(0,2)n")
                .param("id",booking).param("target",TARGET).update();
        fixture=new Fixture(booking,bookingCode,"IW-H-"+suffix,supplierId);
        clock=new MutableClock(Instant.parse("2026-11-25T00:00:00Z"));
        supplierCalls=new AtomicInteger(); requestIds=new CopyOnWriteArrayList<>(); bookingUnlocked=new AtomicBoolean();
        supplierStatus=200; hotelStatus=200; onSupplier=()->{}; onHotel=()->{};
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        providerThreads=Executors.newCachedThreadPool(); provider.setExecutor(providerThreads);
        provider.createContext("/",this::reply); provider.start();
        jobs=worker();
        reports=new IncidentReportService(jdbc,manager,jobs,clock);
        reports.submit(()->"demo-customer-001",fixture.code(),"incident-"+suffix,"CHECKIN_FAILED",true,"합성 고객 신고");
        jobCode=jdbc.sql("SELECT job_code FROM jobs WHERE booking_id=:id").param("id",fixture.id()).query(String.class).single();
    }

    @AfterEach void stopOnlyOwnedProvider() { provider.stop(0); providerThreads.shutdownNow(); }

    @Test void supplier503ThenHotel202ThenValidatedReplySurvivesWorkerRestartWithOneStableRequest() {
        supplierStatus=503;
        assertEquals(IncidentRecheckJobs.State.WAITING,jobs.execute(jobCode).state());
        assertEquals(1,supplierCalls.get()); assertTrue(bookingUnlocked.get()); assertNoFacts();
        jobs=worker(); // durable state, no in-memory retry authority
        assertEquals(IncidentRecheckJobs.State.WAITING,jobs.execute(jobCode).state());
        assertEquals(1,supplierCalls.get());
        clock.advance(31); supplierStatus=200; hotelStatus=202;
        assertEquals("HOTEL_PENDING",jobs.execute(jobCode).errorCode()); assertNoFacts();
        clock.advance(31); hotelStatus=200;
        var done=jobs.execute(jobCode);
        assertEquals(IncidentRecheckJobs.State.COMPLETED,done.state());
        assertEquals(List.of(jobCode,jobCode),requestIds);
        assertEquals(2L,count("evidence_records")); assertEquals(1L,count("cases"));
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM booking_nights WHERE booking_id=:id AND fulfillment_status='UNAVAILABLE' AND fact_status='VERIFIED'")
                .param("id",fixture.id()).query(Long.class).single());
        assertEquals(2,jdbc.sql("SELECT attempt_count FROM jobs WHERE job_code=:code").param("code",jobCode).query(Integer.class).single());
        assertEquals(IncidentRecheckJobs.State.COMPLETED,jobs.execute(jobCode).state());
        assertEquals(3,supplierCalls.get());
    }

    @Test void exhaustedUpstreamFailureKeepsTheIncidentOpenAndAddsRequiredDirectHotelCheck() {
        supplierStatus=503;
        for(int attempt=0;attempt<3;attempt++) { jobs.execute(jobCode); clock.advance(31); }
        var terminal=jobs.execute(jobCode);
        assertEquals(IncidentRecheckJobs.State.FAILED,terminal.state());
        assertEquals("ATTEMPTS_EXHAUSTED",terminal.errorCode());
        assertEquals(3,supplierCalls.get()); assertNoFacts();
        assertEquals("OPEN",jdbc.sql("SELECT status FROM cases WHERE booking_id=:id").param("id",fixture.id()).query(String.class).single());
        assertEquals(1L,jdbc.sql("SELECT count(*) FROM case_tasks WHERE booking_id=:id AND task_type='VERIFY_HOTEL' AND is_required AND status='TODO'")
                .param("id",fixture.id()).query(Long.class).single());
    }

    @Test void twoWorkersCannotSendConcurrentRequestsForOneClaim() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        onSupplier=()-> { entered.countDown(); try { assertTrue(release.await(10,TimeUnit.SECONDS)); }
            catch(InterruptedException stopped) { Thread.currentThread().interrupt(); throw new RuntimeException(stopped); } };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->jobs.execute(jobCode)); assertTrue(entered.await(10,TimeUnit.SECONDS));
            try { assertEquals(IncidentRecheckJobs.State.RUNNING,pool.submit(()->worker().execute(jobCode)).get(5,TimeUnit.SECONDS).state()); }
            finally { release.countDown(); }
            assertEquals(IncidentRecheckJobs.State.COMPLETED,first.get(10,TimeUnit.SECONDS).state());
        }
        assertEquals(1,supplierCalls.get()); assertEquals(List.of(jobCode),requestIds); assertEquals(2L,count("evidence_records"));
    }

    @Test void staleExecutionCannotCommitRoomFactsOrEvidenceAndLeaseRecoveryKeepsRequestId() {
        onHotel=()->jdbc.sql("UPDATE jobs SET result_payload=jsonb_set(result_payload,'{claimToken}',to_jsonb(CAST(:token AS text))) WHERE job_code=:code")
                .param("token",UUID.randomUUID().toString()).param("code",jobCode).update();
        assertEquals(IncidentRecheckJobs.State.RUNNING,jobs.execute(jobCode).state()); assertNoFacts();
        onHotel=()->{}; clock.advance(61);
        assertEquals(IncidentRecheckJobs.State.COMPLETED,worker().execute(jobCode).state());
        assertEquals(List.of(jobCode,jobCode),requestIds); assertEquals(2L,count("evidence_records"));
    }

    @Test void unsupportedBookingStillAcceptsTheReportAndLeavesManualWorkWithoutProviderCalls() {
        // A new report after the first job becomes terminal is needed to freeze the changed source.
        jdbc.sql("UPDATE jobs SET status='FAILED',finished_at=now() WHERE job_code=:code").param("code",jobCode).update();
        jdbc.sql("UPDATE bookings SET supplier_status='CANCELLED' WHERE id=:id").param("id",fixture.id()).update();
        var receipt=reports.submit(()->"demo-customer-001",fixture.code(),"unsupported-new","CHECKIN_FAILED",false,"취소 상태여도 신고 접수");
        assertEquals("PENDING",receipt.verificationStatus());
        String fresh=jdbc.sql("SELECT job_code FROM jobs WHERE booking_id=:id AND status='PENDING'").param("id",fixture.id()).query(String.class).single();
        assertEquals("UNSUPPORTED_BOOKING_SOURCE",jobs.execute(fresh).errorCode());
        assertEquals(0,supplierCalls.get()); assertNoFacts();
    }

    @Test void linkedRiskWorkIsDelegatedWithoutChangingItsOwnerOrRequest() {
        jdbc.sql("UPDATE jobs SET status='FAILED',finished_at=now() WHERE job_code=:code").param("code",jobCode).update();
        String riskCode="RISK-999999-"+fixture.id();
        jdbc.sql("""
                INSERT INTO jobs(job_code,booking_id,kind,trigger_type,status,parameters,next_attempt_at)
                VALUES(:code,:booking,'RECHECK','SCHEDULED','PENDING','{"protocol":"risk-recheck-v1","frozen":"untouched"}',
                    TIMESTAMPTZ '2000-01-01T00:00:00Z')
                """).param("code",riskCode).param("booking",fixture.id()).update();
        reports.submit(()->"demo-customer-001",fixture.code(),"linked-risk","CHECKIN_FAILED",true,"기존 확인 작업에 신고 추가");
        var risk=mock(RiskRecheckJobs.class);
        when(risk.execute(riskCode)).thenReturn(new RiskRecheckJobs.Execution(riskCode,fixture.code(),riskCode,
                RiskRecheckJobs.State.WAITING,"HOTEL_PENDING",null));
        var delegated=new IncidentRecheckJobs(IncidentEvidenceTestFactory.http(jdbc,manager,
                "http://127.0.0.1:"+provider.getAddress().getPort(),"test-only-token"),jdbc,manager,clock,
                IncidentRecheckJobs.RetryPolicy.defaults(),risk);
        assertEquals(riskCode,delegated.runDue(1).getFirst().jobCode());
        verify(risk).execute(riskCode); assertEquals(0,supplierCalls.get());
        assertEquals("untouched",jdbc.sql("SELECT parameters->>'frozen' FROM jobs WHERE job_code=:code")
                .param("code",riskCode).query(String.class).single());
        jdbc.sql("UPDATE jobs SET status='FAILED',finished_at=now() WHERE job_code=:code").param("code",riskCode).update();
    }

    private IncidentRecheckJobs worker() {
        var evidence=IncidentEvidenceTestFactory.http(jdbc,manager,"http://127.0.0.1:"+provider.getAddress().getPort(),"test-only-token");
        return new IncidentRecheckJobs(evidence,jdbc,manager,clock,IncidentRecheckJobs.RetryPolicy.defaults());
    }

    private void reply(HttpExchange exchange) throws IOException {
        assertEquals("Bearer test-only-token",exchange.getRequestHeaders().getFirst("Authorization"));
        Object response; int status;
        if(exchange.getRequestURI().getPath().startsWith("/mock-supplier/")) {
            supplierCalls.incrementAndGet();
            jdbc.sql("SELECT id FROM bookings WHERE id=:id FOR UPDATE NOWAIT").param("id",fixture.id()).query(Long.class).single();
            bookingUnlocked.set(true); onSupplier.run(); status=supplierStatus;
            response=status==200?Map.of("supplierBookingId",fixture.supplier(),"referenceBookingId",fixture.code(),
                    "propertyId",fixture.hotel(),"contractStatus","CONFIRMED","stay",Map.of("checkInDate",TARGET,
                    "checkOutDate",TARGET.plusDays(3),"adults",2,"rooms",1),"updatedAt","2026-11-10T12:00:00+09:00")
                    :Map.of("code","UPSTREAM_UNAVAILABLE","detail","synthetic");
        } else {
            var body=JSON.readTree(exchange.getRequestBody().readAllBytes());
            String request=body.path("reconfirmationRequestId").asString(); requestIds.add(request); onHotel.run(); status=hotelStatus;
            response=status==202?Map.of("reconfirmationRequestId",request,"reconfirmationId","SIM-PENDING","status","PENDING",
                    "receivedAt","2026-11-10T12:00:00+09:00")
                    :Map.of("reconfirmationRequestId",request,"reconfirmationId","SIM-REPLY","status","RESPONDED",
                    "supplierBookingId",fixture.supplier(),"propertyId",fixture.hotel(),"nights",List.of(
                    Map.of("stayDate",TARGET,"reportedProvisionStatus","UNAVAILABLE","message","합성 첫날 불가"),
                    Map.of("stayDate",TARGET.plusDays(1),"reportedProvisionStatus","UNKNOWN","message","합성 미확인"),
                    Map.of("stayDate",TARGET.plusDays(2),"reportedProvisionStatus","UNKNOWN","message","합성 미확인")),
                    "occurredAt","2026-11-10T12:00:00+09:00","sentAt","2026-11-10T12:01:00+09:00");
        }
        byte[] bytes=JSON.writeValueAsString(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
    }

    private long count(String table) { return jdbc.sql("SELECT count(*) FROM "+table+" WHERE booking_id=:id")
            .param("id",fixture.id()).query(Long.class).single(); }
    private void assertNoFacts() {
        assertEquals(0L,count("evidence_records"));
        assertEquals(3L,jdbc.sql("SELECT count(*) FROM booking_nights WHERE booking_id=:id AND fulfillment_status='UNKNOWN' AND fact_status='UNCONFIRMED'")
                .param("id",fixture.id()).query(Long.class).single());
    }
    private record Fixture(long id,String code,String hotel,String supplier) {}
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant initial) { now=initial; }
        void advance(long seconds) { now=now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
