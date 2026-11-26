package com.csro.backend.hotelanalysis;

import java.util.UUID;

/** Safe local diagnostics only: never retains source text, provider body, credentials or an upstream cause. */
public final class HotelReplyAnalysisException extends RuntimeException {
    private final String code;
    private final String traceId;
    private final Integer httpStatus;

    public HotelReplyAnalysisException(String safeCode) {
        this(safeCode, null);
    }

    public HotelReplyAnalysisException(String safeCode, Integer httpStatus) {
        super(normalize(safeCode));
        this.code = normalize(safeCode);
        this.traceId = UUID.randomUUID().toString();
        this.httpStatus = httpStatus;
    }

    private static String normalize(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,79}") ? value : "ANALYSIS_INTERNAL_ERROR";
    }

    public String code() { return code; }
    public String traceId() { return traceId; }
    public Integer httpStatus() { return httpStatus; }
}
