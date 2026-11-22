package com.csro.backend.risk;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.csro.backend.reconfirmation.BookingReconfirmationEvidenceService;
import com.csro.backend.risk.RiskMlClient.Assessment;

import tools.jackson.databind.json.JsonMapper;

/**
 * Internal, explicitly invoked bridge from model score to local Mock reconfirmation.
 * The cohort is a current simulated fixture with a creation-time cutoff, not a
 * reconstructed historical event snapshot. This service is not a scheduler or a
 * historical-state reconstruction, and repeating an assessment creates a new
 * assessment row. Selected reconfirmations use the durable, manually drained jobs.
 */
@Service
public class RiskRecheckService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COHORT_SQL = """
            SELECT b.id, b.hotel_id, b.booking_code, b.supplier_code, b.supplier_booking_id,
                   b.check_out_date, b.adults, b.rooms, h.data_source,
                   EXISTS (SELECT 1 FROM booking_nights n
                           WHERE n.booking_id = b.id AND n.fact_status = 'CONFLICT') AS has_conflict
            FROM bookings b JOIN hotels h ON h.id = b.hotel_id
            WHERE h.hotel_code = :hotelId AND b.check_in_date = :targetDate
                AND b.supplier_status = 'CONFIRMED' AND b.created_at <= :asOf
            ORDER BY b.id
            """;

    private final RiskMlClient ml;
    private final RiskRecheckJobs jobs;
    private final JdbcClient jdbc;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public RiskRecheckService(RiskMlClient ml, BookingReconfirmationEvidenceService reconfirmation,
                             JdbcClient jdbc, PlatformTransactionManager manager) {
        this(ml, reconfirmation, jdbc, manager, new RiskRecheckJobs(reconfirmation, jdbc, manager));
    }

    @Autowired
    public RiskRecheckService(RiskMlClient ml, BookingReconfirmationEvidenceService reconfirmation,
                             JdbcClient jdbc, PlatformTransactionManager manager, RiskRecheckJobs jobs) {
        this.ml = ml;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.read = new TransactionTemplate(manager);
        this.read.setReadOnly(true);
        this.read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.write = new TransactionTemplate(manager);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * DB read, ML HTTP, DB write, and subsequent reconfirmation HTTP are separate
     * stages. Only accepted reconfirmation evidence can cause a case or night fact.
     */
    public Outcome assessAndReconfirm(RiskRequest request, RiskSelectionPolicy policy) {
        return assessAndReconfirm(request, policy, null);
    }

    /** A local event projection can additionally bind the exact prediction-time booking codes. */
    public Outcome assessAndReconfirm(RiskRequest request, RiskSelectionPolicy policy,
                                     List<String> expectedCohortBookingCodes) {
        // Do not wrap this method in @Transactional(NEVER): Spring may create
        // an empty synchronization scope even without a real transaction.
        // This guard also covers direct calls that do not pass through a proxy.
        requireNoCallerTransaction();
        if (request == null || policy == null) {
            throw new IllegalArgumentException("Risk request and an explicit selection policy are required");
        }
        request.validate();
        if (expectedCohortBookingCodes != null && (expectedCohortBookingCodes.isEmpty()
                || expectedCohortBookingCodes.stream().anyMatch(code -> code == null || code.isBlank())
                || new HashSet<>(expectedCohortBookingCodes).size() != expectedCohortBookingCodes.size()
                || expectedCohortBookingCodes.size() != request.features().targetConfirmedBookingCount())) {
            throw new IllegalArgumentException("Invalid prediction-time booking cohort");
        }
        List<CohortBooking> cohort = Objects.requireNonNull(
                read.execute(status -> loadCohort(request, false)), "Risk cohort read returned no result");
        if (expectedCohortBookingCodes != null && !new HashSet<>(expectedCohortBookingCodes).equals(
                new HashSet<>(cohort.stream().map(CohortBooking::bookingCode).toList()))) {
            throw new IllegalArgumentException("Prediction-time booking codes do not match the current fixture cohort");
        }

        // No database transaction or booking lock is held during the ML call.
        requireNoCallerTransaction();
        Assessment assessment = ml.assess(request);
        validateAssessment(request, assessment);
        boolean selected = policy.selects(assessment.riskScore());

        // Store just the six request features. Policy/demonstration metadata is
        // returned separately and never smuggled into the model input snapshot.
        String features = JSON.writeValueAsString(request.features());
        SavedAssessment saved = Objects.requireNonNull(write.execute(status -> {
            lockSimulatedHotel(request);
            List<CohortBooking> current = loadCohort(request, true);
            if (!cohort.equals(current)) {
                throw new IllegalStateException("Risk cohort changed during model assessment");
            }
            long id = jdbc.sql("""
                    INSERT INTO risk_assessments (hotel_id, target_check_in_date, as_of, risk_score,
                        model_version, feature_set_version, feature_snapshot)
                    VALUES (:hotelId, :targetDate, :asOf, :score, :modelVersion, :featureSetVersion,
                        CAST(:features AS jsonb))
                    RETURNING id
                    """).param("hotelId", cohort.getFirst().hotelId())
                    .param("targetDate", request.targetCheckInDate()).param("asOf", request.asOf())
                    .param("score", BigDecimal.valueOf(assessment.riskScore()))
                    .param("modelVersion", assessment.modelVersion())
                    .param("featureSetVersion", assessment.featureSetVersion())
                    .param("features", features).query(Long.class).single();
            List<RiskRecheckJobs.JobRef> selectedJobs = selected
                    ? jobs.enqueueSelected(id, request, policy, assessment.riskScore(),
                            cohort.stream().map(booking -> new RiskRecheckJobs.Scope(
                                    booking.id(), booking.bookingCode(), request.hotelId(), booking.supplierCode(),
                                    booking.supplierBookingId(), request.targetCheckInDate(), booking.checkOutDate(),
                                    booking.adults(), booking.rooms())).toList())
                    : List.of();
            return new SavedAssessment(id, selectedJobs);
        }), "Risk assessment write returned no ID");

        // The assessment and selected jobs commit together before HTTP. A crash
        // or retryable failure leaves a discoverable job with the same request ID.
        List<RecheckOutcome> rechecks = new ArrayList<>();
        for (var job : saved.jobs()) {
            requireNoCallerTransaction();
            var execution = jobs.execute(job.jobCode());
            RecheckStatus status = switch (execution.state()) {
                case COMPLETED -> RecheckStatus.COMPLETED;
                case FAILED -> RecheckStatus.FAILED;
                case PENDING, RUNNING, WAITING -> RecheckStatus.PENDING;
            };
            rechecks.add(new RecheckOutcome(execution.bookingCode(), execution.requestId(), status,
                    execution.errorCode(), execution.caseCode()));
        }
        return new Outcome(saved.id(), assessment.riskScore(), policy, policy.threshold(), selected,
                assessment.modelVersion(), assessment.featureSetVersion(), rechecks);
    }

    private List<CohortBooking> loadCohort(RiskRequest request, boolean forUpdate) {
        List<CohortBooking> rows = jdbc.sql(COHORT_SQL + (forUpdate ? " FOR UPDATE OF b" : ""))
                .param("hotelId", request.hotelId()).param("targetDate", request.targetCheckInDate())
                .param("asOf", request.asOf())
                .query((rs, row) -> new CohortBooking(rs.getLong("id"), rs.getLong("hotel_id"),
                        rs.getString("booking_code"), rs.getString("supplier_code"),
                        rs.getString("supplier_booking_id"), rs.getObject("check_out_date", LocalDate.class),
                        rs.getInt("adults"), rs.getInt("rooms"), rs.getString("data_source"),
                        rs.getBoolean("has_conflict"))).list();
        if (rows.isEmpty()) {
            throw new IllegalStateException("No eligible simulated booking cohort is available");
        }
        if (rows.stream().anyMatch(row -> !"SIMULATED".equals(row.dataSource())
                || !"MOCK_SUPPLIER".equals(row.supplierCode()) || row.hasConflict())) {
            throw new IllegalStateException("Risk reconfirmation supports only simulated Mock bookings without conflicts");
        }
        if (rows.size() != request.features().targetConfirmedBookingCount()) {
            throw new IllegalArgumentException("Target confirmed booking count must match the current fixture cohort");
        }
        return List.copyOf(rows);
    }

    private void lockSimulatedHotel(RiskRequest request) {
        jdbc.sql("""
                SELECT id FROM hotels WHERE hotel_code = :hotelId AND data_source = 'SIMULATED'
                FOR UPDATE
                """).param("hotelId", request.hotelId()).query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("Simulated risk hotel is no longer available"));
    }

    private static void validateAssessment(RiskRequest request, Assessment assessment) {
        if (assessment == null || !request.hotelId().equals(assessment.hotelId())
                || !request.targetCheckInDate().equals(assessment.targetCheckInDate())
                || assessment.asOf() == null || !request.asOf().isEqual(assessment.asOf())
                || !request.featureSetVersion().equals(assessment.featureSetVersion())
                || !RiskMlClient.MODEL_VERSION.equals(assessment.modelVersion())) {
            throw new IllegalStateException("Model assessment does not match the requested prediction scope");
        }
    }

    private static void requireNoCallerTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Risk assessment and external reconfirmation require no caller transaction");
        }
    }

    public enum RecheckStatus { COMPLETED, PENDING, FAILED }

    public record RecheckOutcome(String bookingCode, String requestId, RecheckStatus status,
                                 String errorCode, String caseCode) {}

    public record Outcome(long assessmentId, double rawRiskScore, RiskSelectionPolicy policy,
                          double threshold, boolean selected, String modelVersion,
                          String featureSetVersion, List<RecheckOutcome> rechecks) {
        public Outcome {
            rechecks = List.copyOf(rechecks);
        }
    }

    private record CohortBooking(long id, long hotelId, String bookingCode, String supplierCode,
                                 String supplierBookingId, LocalDate checkOutDate, int adults, int rooms,
                                 String dataSource, boolean hasConflict) {}

    private record SavedAssessment(long id, List<RiskRecheckJobs.JobRef> jobs) {}
}
