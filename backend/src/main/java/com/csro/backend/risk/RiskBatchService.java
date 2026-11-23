package com.csro.backend.risk;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit batch only. It does not start timers or add another model-inference path. */
@Service
public final class RiskBatchService {
    private final RiskRecheckService assessments;
    private final RiskRecheckJobs jobs;
    private final JdbcClient jdbc;

    public RiskBatchService(RiskRecheckService assessments, RiskRecheckJobs jobs, JdbcClient jdbc) {
        this.assessments = assessments;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    BatchResult run(RiskBatchManifest manifest, RiskSelectionPolicy policy, int maxTargets, int jobLimit) {
        requireNoTransaction();
        Objects.requireNonNull(manifest, "A risk batch manifest is required");
        Objects.requireNonNull(policy, "An explicit risk policy is required");
        if (maxTargets < 1 || maxTargets > 100 || jobLimit < 1 || jobLimit > 100) {
            throw new IllegalArgumentException("Risk batch bounds must be between 1 and 100");
        }
        Map<String, RiskEventProjection> byHotel = new HashMap<>();
        List<String> inputErrors = new ArrayList<>();
        for (var path : manifest.projections()) {
            RiskEventProjection projection;
            try {
                projection = RiskEventProjection.read(path);
                if (!projection.request().targetCheckInDate().equals(manifest.targetCheckInDate())) {
                    inputErrors.add("PROJECTION_DATE_MISMATCH");
                    continue;
                }
            } catch (IOException | IllegalArgumentException ex) {
                inputErrors.add("PROJECTION_UNREADABLE_OR_INVALID");
                continue;
            }
            if (byHotel.putIfAbsent(projection.request().hotelId(), projection) != null) {
                throw new IllegalArgumentException("Multiple projections for one hotel are ambiguous");
            }
        }

        var asOf = manifest.targetCheckInDate().minusDays(1).atTime(9, 0).atOffset(ZoneOffset.ofHours(9));
        // Include all suppliers here. The existing service rejects a mixed/non-Mock
        // cohort rather than quietly excluding those bookings from the model count.
        List<String> hotels = jdbc.sql("""
                SELECT DISTINCT h.hotel_code FROM bookings b JOIN hotels h ON h.id=b.hotel_id
                WHERE h.data_source='SIMULATED' AND b.supplier_status='CONFIRMED'
                    AND b.check_in_date=:target AND b.created_at<=:asOf
                ORDER BY h.hotel_code LIMIT :limit
                """).param("target", manifest.targetCheckInDate()).param("asOf", asOf)
                .param("limit", maxTargets + 1).query(String.class).list();
        if (hotels.size() > maxTargets) {
            throw new IllegalStateException("Eligible hotel count exceeds the explicit batch bound");
        }
        List<HotelResult> results = new ArrayList<>();
        for (String hotel : hotels) {
            var projection = byHotel.get(hotel);
            if (projection == null) {
                results.add(new HotelResult(hotel, State.FAILED, null, "MISSING_PROJECTION"));
                continue;
            }
            try {
                var result = assessments.assessBatchAndReconfirm(projection.request(), policy,
                        projection.cohortBookingCodes());
                results.add(new HotelResult(hotel, result.reusedAssessment() ? State.REUSED : State.ASSESSED,
                        result.outcome(), null));
            } catch (RiskMlException ex) {
                results.add(new HotelResult(hotel, State.FAILED, null, "ML_" + ex.kind().name()));
            } catch (DataAccessException ex) {
                results.add(new HotelResult(hotel, State.FAILED, null, "DATABASE_ERROR"));
            } catch (IllegalArgumentException ex) {
                results.add(new HotelResult(hotel, State.FAILED, null, "COHORT_OR_INPUT_INVALID"));
            } catch (IllegalStateException ex) {
                results.add(new HotelResult(hotel, State.FAILED, null, "COHORT_OR_JOB_CONFLICT"));
            } catch (RuntimeException ex) {
                results.add(new HotelResult(hotel, State.FAILED, null, "UNEXPECTED_ERROR"));
            }
        }

        // Due-job recovery is independent of today's eligible hotels or their ML
        // failures. Only this implementation's existing RECHECK jobs are drained.
        List<RiskRecheckJobs.Execution> resumed;
        String workerError = null;
        try {
            resumed = jobs.runDue(jobLimit);
        } catch (RuntimeException ex) {
            resumed = List.of();
            workerError = "JOB_DRAIN_FAILED";
        }
        boolean successful = inputErrors.isEmpty() && workerError == null
                && results.stream().noneMatch(r -> r.state() == State.FAILED
                    || r.assessment().rechecks().stream()
                        .anyMatch(job -> job.status() == RiskRecheckService.RecheckStatus.FAILED))
                && resumed.stream().noneMatch(job -> job.state() == RiskRecheckJobs.State.FAILED);
        return new BatchResult(manifest.targetCheckInDate(), hotels.size(), results, resumed, inputErrors,
                (int) byHotel.keySet().stream().filter(hotel -> !hotels.contains(hotel)).count(), workerError, successful);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Risk batches require no caller transaction");
        }
    }

    enum State { ASSESSED, REUSED, FAILED }
    record HotelResult(String hotelId, State state, RiskRecheckService.Outcome assessment, String errorCode) {}
    record BatchResult(LocalDate targetCheckInDate, int eligibleHotels, List<HotelResult> hotels,
                       List<RiskRecheckJobs.Execution> resumedJobs, List<String> inputErrors,
                       int unusedProjections, String workerError, boolean successful) {
        BatchResult {
            hotels = List.copyOf(hotels);
            resumedJobs = List.copyOf(resumedJobs);
            inputErrors = List.copyOf(inputErrors);
        }
    }
}
