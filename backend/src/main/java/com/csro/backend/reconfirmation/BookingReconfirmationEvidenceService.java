package com.csro.backend.reconfirmation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.csro.backend.reconfirmation.BookingReconfirmationResult.EvidenceBody;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Night;

@Service
public class BookingReconfirmationEvidenceService {

    private static final String SUPPLIER = "MOCK_SUPPLIER";
    private static final String HOTEL = "MOCK_HOTEL";

    private final BookingReconfirmationService reconfirmation;
    private final ReconfirmationBookingRepository bookings;
    private final JdbcClient jdbc;
    private final TransactionTemplate write;

    public BookingReconfirmationEvidenceService(BookingReconfirmationService reconfirmation,
                                                ReconfirmationBookingRepository bookings,
                                                JdbcClient jdbc, PlatformTransactionManager manager) {
        this.reconfirmation = reconfirmation;
        this.bookings = bookings;
        this.jdbc = jdbc;
        this.write = new TransactionTemplate(manager);
        this.write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Outcome reconfirmAndApply(String bookingCode, String requestId) {
        BookingReconfirmationResult result = reconfirmation.reconfirm(bookingCode, requestId);
        return applyReply(result, null);
    }

    /** Internal durable-job entry point; the job and frozen booking scope never come from public HTTP. */
    @Transactional(propagation = Propagation.NEVER)
    public Outcome reconfirmAndApply(String bookingCode, String requestId,
                                     ReconfirmationScope expectedScope, Long jobId) {
        if (jobId != null && jobId < 1) {
            throw new IllegalArgumentException("Invalid reconfirmation job reference");
        }
        BookingReconfirmationResult result = reconfirmation.reconfirm(bookingCode, requestId, expectedScope);
        return applyReply(result, jobId);
    }

    private Outcome applyReply(BookingReconfirmationResult result, Long jobId) {
        if (!(result.hotel() instanceof HotelReply reply)) {
            return new Outcome(result, null, null, null, false);
        }
        if (!result.supplierEvidence().localMock() || !result.hotelEvidence().localMock()) {
            throw new IllegalStateException("Evidence application requires the local simulation source");
        }
        return write.execute(status -> apply(result, reply, jobId));
    }

    private Outcome apply(BookingReconfirmationResult result, HotelReply reply, Long jobId) {
        long bookingId = jdbc.sql("""
                SELECT b.id FROM bookings b JOIN hotels h ON h.id = b.hotel_id
                WHERE b.booking_code = :code AND b.supplier_code = 'MOCK_SUPPLIER'
                    AND h.data_source = 'SIMULATED'
                FOR UPDATE OF b
                """).param("code", result.bookingCode()).query(Long.class).optional()
                .orElseThrow(() -> new IllegalStateException("Simulated booking no longer available"));

        if (jobId != null) {
            // Deliberately no job-row lock: workers claim jobs before locking a booking.
            boolean belongsToBooking = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM jobs
                        WHERE id = :jobId AND booking_id = :bookingId AND kind = 'RECHECK')
                    """).param("jobId", jobId).param("bookingId", bookingId).query(Boolean.class).single();
            if (!belongsToBooking) {
                throw new IllegalStateException("Reconfirmation job does not match the booking");
            }
        }

        Map<LocalDate, StoredNight> nights = new HashMap<>();
        jdbc.sql("""
                SELECT id, stay_date, fulfillment_status, fact_status
                FROM booking_nights WHERE booking_id = :id ORDER BY stay_date FOR UPDATE
                """).param("id", bookingId).query((rs, row) -> new StoredNight(
                rs.getLong("id"), rs.getObject("stay_date", LocalDate.class),
                rs.getString("fulfillment_status"), rs.getString("fact_status")))
                .list().forEach(night -> nights.put(night.date(), night));
        var booking = bookings.findByCode(result.bookingCode()).orElseThrow();
        BookingReconfirmationService.validateBookingNights(booking);
        BookingReconfirmationService.validateSupplier(booking, result.supplier());
        BookingReconfirmationService.validateReply(booking, reply.reconfirmationRequestId(), reply);

        // The key identifies this outbound request, not the Mock's fixed reconfirmationId.
        String eventKey = "recheck-request:" + sha256(bookingId + ":" + reply.reconfirmationRequestId());
        String supplierHash = sha256(result.supplierEvidence().rawJson());
        String hotelHash = sha256(result.hotelEvidence().rawJson());
        Optional<Evidence> existingSupplier = findEvidence(bookingId, SUPPLIER, eventKey);
        Optional<Evidence> existingHotel = findEvidence(bookingId, HOTEL, eventKey);
        if (existingSupplier.isPresent() || existingHotel.isPresent()) {
            if (existingSupplier.filter(row -> supplierHash.equals(row.hash())
                            && Objects.equals(jobId, row.jobId())).isEmpty()
                    || existingHotel.filter(row -> hotelHash.equals(row.hash())
                            && Objects.equals(jobId, row.jobId())
                            && Objects.equals(result.hotelEvidence().requestJson(), row.requestJson())).isEmpty()) {
                throw new RequestBodyConflictException();
            }
            return new Outcome(result, existingSupplier.orElseThrow().id(), existingHotel.orElseThrow().id(),
                    activeCase(bookingId).map(ActiveCase::code).orElse(null), true);
        }

        long supplierId = insertEvidence(bookingId, "SUPPLIER_API", SUPPLIER, eventKey,
                supplierHash, result.supplierEvidence(), result.supplier().updatedAt(), jobId);
        long hotelId = insertEvidence(bookingId, "HOTEL_API", HOTEL, eventKey,
                hotelHash, result.hotelEvidence(), reply.occurredAt(), jobId);

        for (Night claim : reply.nights()) {
            applyNight(bookingId, hotelId, nights.get(claim.stayDate()), claim);
        }

        Optional<ActiveCase> active = activeCase(bookingId);
        boolean hasVerifiedUnavailability = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM booking_nights WHERE booking_id = :id
                    AND fact_status = 'VERIFIED' AND fulfillment_status = 'UNAVAILABLE')
                """).param("id", bookingId).query(Boolean.class).single();
        if (hasVerifiedUnavailability) {
            ActiveCase current = active.orElseGet(() -> createCase(bookingId));
            ensureFirstTask(bookingId, current.id());
            active = Optional.of(current);
        }
        return new Outcome(result, supplierId, hotelId, active.map(ActiveCase::code).orElse(null), false);
    }

    private Optional<Evidence> findEvidence(long bookingId, String provider, String eventKey) {
        return jdbc.sql("""
                SELECT id, payload_hash, raw_payload ->> 'requestJson' AS request_json, job_id
                FROM evidence_records
                WHERE booking_id = :bookingId AND provider_code = :provider AND provider_event_id = :eventKey
                """)
                .param("bookingId", bookingId).param("provider", provider).param("eventKey", eventKey)
                .query((rs, row) -> new Evidence(rs.getLong("id"), rs.getString("payload_hash"),
                        rs.getString("request_json"), rs.getObject("job_id", Long.class))).optional();
    }

    private long insertEvidence(long bookingId, String source, String provider, String eventKey, String hash,
                                EvidenceBody evidence, OffsetDateTime occurredAt, Long jobId) {
        // Outbound Bearer authenticates the caller. The local HTTP Mock does not authenticate itself.
        return jdbc.sql("""
                INSERT INTO evidence_records (booking_id, job_id, source_type, provider_code, provider_event_id,
                    source_authenticated, raw_text, raw_payload, occurred_at, received_at, payload_hash)
                VALUES (:bookingId, :jobId, :source, :provider, :eventKey, false, :raw,
                    CASE WHEN CAST(:request AS text) IS NULL THEN CAST(:raw AS jsonb)
                         ELSE jsonb_build_object('requestJson', CAST(:request AS text), 'response', CAST(:raw AS jsonb))
                    END,
                    :occurredAt, :receivedAt, :hash)
                RETURNING id
                """)
                .param("bookingId", bookingId).param("jobId", jobId).param("source", source).param("provider", provider)
                .param("eventKey", eventKey).param("raw", evidence.rawJson()).param("request", evidence.requestJson())
                .param("occurredAt", occurredAt)
                .param("receivedAt", evidence.receivedAt()).param("hash", hash).query(Long.class).single();
    }

    private void applyNight(long bookingId, long evidenceId, StoredNight night, Night claim) {
        String reported = claim.reportedProvisionStatus();
        boolean known = !"UNKNOWN".equals(reported);
        boolean accept = known && "UNCONFIRMED".equals(night.fact());
        long assertionId = jdbc.sql("""
                INSERT INTO evidence_nights (booking_id, evidence_id, booking_night_id, reported_status,
                    review_status, supporting_text, reviewed_at)
                VALUES (:bookingId, :evidenceId, :nightId, :reported, :review, :message,
                    CASE WHEN :accepted THEN now() END)
                RETURNING id
                """).param("bookingId", bookingId).param("evidenceId", evidenceId).param("nightId", night.id())
                .param("reported", reported).param("review", accept ? "ACCEPTED" : "PENDING_REVIEW")
                .param("message", claim.message()).param("accepted", accept)
                .query(Long.class).single();
        if (accept) {
            jdbc.sql("""
                    UPDATE booking_nights SET fulfillment_status = :reported, fact_status = 'VERIFIED',
                        accepted_assertion_id = :assertionId, updated_at = now()
                    WHERE id = :nightId
                    """).param("reported", reported).param("assertionId", assertionId).param("nightId", night.id())
                    .update();
        } else if (known && "VERIFIED".equals(night.fact()) && !reported.equals(night.fulfillment())) {
            jdbc.sql("""
                    UPDATE booking_nights SET fact_status = 'CONFLICT', updated_at = now()
                    WHERE id = :nightId
                    """).param("nightId", night.id()).update();
        }
    }

    private Optional<ActiveCase> activeCase(long bookingId) {
        return jdbc.sql("""
                SELECT id, case_code FROM cases WHERE booking_id = :bookingId
                    AND status IN ('OPEN', 'IN_PROGRESS', 'WAITING') FOR UPDATE
                """).param("bookingId", bookingId)
                .query((rs, row) -> new ActiveCase(rs.getLong("id"), rs.getString("case_code"))).optional();
    }

    private ActiveCase createCase(long bookingId) {
        while (true) {
            Optional<ActiveCase> candidate = jdbc.sql("""
                    WITH candidate AS (SELECT nextval(pg_get_serial_sequence('cases', 'id')) AS id)
                    INSERT INTO cases (id, case_code, booking_id, status, priority)
                    SELECT id, 'C-' || lpad(id::text, greatest(3, length(id::text)), '0'), :bookingId,
                        'OPEN', 'URGENT' FROM candidate
                    ON CONFLICT DO NOTHING
                    RETURNING id, case_code
                    """).param("bookingId", bookingId)
                    .query((rs, row) -> new ActiveCase(rs.getLong("id"), rs.getString("case_code"))).optional();
            if (candidate.isEmpty()) {
                candidate = activeCase(bookingId);
            }
            if (candidate.isPresent()) {
                return candidate.orElseThrow();
            }
        }
    }

    private void ensureFirstTask(long bookingId, long caseId) {
        jdbc.sql("""
                INSERT INTO case_tasks (booking_id, case_id, task_type, status)
                SELECT :bookingId, :caseId, 'CONTACT_CUSTOMER', 'TODO'
                WHERE NOT EXISTS (
                    SELECT 1 FROM case_tasks WHERE case_id = :caseId AND task_type = 'CONTACT_CUSTOMER'
                )
                """).param("bookingId", bookingId).param("caseId", caseId).update();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public record Outcome(BookingReconfirmationResult result, Long supplierEvidenceId, Long hotelEvidenceId,
                          String activeCaseCode, boolean replayed) {}

    public static final class RequestBodyConflictException extends IllegalStateException {
        RequestBodyConflictException() {
            super("Reconfirmation request was already recorded with different evidence");
        }
    }

    private record StoredNight(long id, LocalDate date, String fulfillment, String fact) {}
    private record Evidence(long id, String hash, String requestJson, Long jobId) {}
    private record ActiveCase(long id, String code) {}
}
