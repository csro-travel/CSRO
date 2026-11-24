package com.csro.backend.risk;

/** Value-free API-023 failure. Raw response bodies, credentials and causes are not retained. */
public final class RiskMlException extends RuntimeException {

    public enum Kind {
        NO_SERVICE_TOKEN, INVALID_REQUEST, AUTHENTICATION, REQUEST_REJECTED, UNAVAILABLE,
        HTTP_ERROR, INVALID_RESPONSE, RESPONSE_TOO_LARGE, TIMEOUT, CONNECTION_FAILURE
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final String code;
    private final String traceId;

    RiskMlException(Kind kind, Integer httpStatus, String code, String traceId) {
        super("API-023 " + kind + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"));
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
        this.traceId = traceId;
    }

    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public String code() { return code; }
    public String traceId() { return traceId; }
}
