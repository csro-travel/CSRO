package com.csro.backend.hotelanalysis;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.csro.backend.hotelanalysis.HotelReplyAnalysisContracts.Analysis;

/** API-024 analysis is a human-review aid, never an instruction to change booking facts. */
@Service
public class HotelReplyAnalysisService {
    private final HotelReplyAnalysisClient client;
    private final HotelReplyAnalysisStore store;

    public HotelReplyAnalysisService(HotelReplyAnalysisClient client, HotelReplyAnalysisStore store) {
        this.client = client;
        this.store = store;
    }

    public Outcome analyze(long evidenceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new HotelReplyAnalysisException("ANALYSIS_TRANSACTION_ACTIVE");
        }
        var prepared = store.prepare(evidenceId);
        Analysis analysis;
        try {
            // prepare's read transaction has ended; no booking/evidence lock crosses HTTP.
            analysis = client.analyze(prepared.input());
            HotelReplyAnalysisClient.validateAnalysis(prepared.input(), analysis);
        } catch (HotelReplyAnalysisException failure) {
            return store.manual(prepared, failure.code(), failure.traceId());
        } catch (RuntimeException failure) {
            // A provider/client bug must not expose its original exception or skip manual work.
            return store.manual(prepared, "ANALYSIS_CLIENT_FAILED", null);
        }
        return store.save(prepared, analysis);
    }

    public enum State { ANALYZED, REUSED, MANUAL_REQUIRED }

    public record Outcome(State status, Long analysisId, String evidenceId, String analysisStatus,
                          String modelVersion, Long manualTaskId, String errorCode, String traceId,
                          boolean requiresHumanVerification) {}
}
