package com.csro.backend.quotes;

/** Sanitized API-025/028 failure: never retains response text, credentials or an exception cause. */
public final class QuoteIntegrationException extends RuntimeException {
    public enum Step { API_025, API_028 }
    public enum Kind {
        INVALID_REQUEST, NO_SERVICE_TOKEN, TRANSACTION_ACTIVE, AUTHENTICATION,
        REQUEST_REJECTED, UNAVAILABLE, HTTP_ERROR, INVALID_RESPONSE, RESPONSE_TOO_LARGE,
        TIMEOUT, CONNECTION_FAILURE
    }

    private final Step step;
    private final Kind kind;
    private final Integer upstreamStatus;
    private final String upstreamCode;
    private final String traceId;

    QuoteIntegrationException(Step step, Kind kind, Integer upstreamStatus, String upstreamCode, String traceId) {
        super(step + ": " + kind);
        this.step = step;
        this.kind = kind;
        this.upstreamStatus = upstreamStatus;
        this.upstreamCode = upstreamCode;
        this.traceId = traceId;
    }

    public Step step() { return step; }
    public Kind kind() { return kind; }
    public Integer upstreamStatus() { return upstreamStatus; }
    public String upstreamCode() { return upstreamCode; }
    public String traceId() { return traceId; }
}
