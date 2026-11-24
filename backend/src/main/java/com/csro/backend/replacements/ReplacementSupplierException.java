package com.csro.backend.replacements;

/** An uncertain supplier boundary result, without credentials, raw body or exception cause. */
public final class ReplacementSupplierException extends RuntimeException {
    public enum Kind {
        REQUEST_NOT_FOUND, UNKNOWN, TRANSACTION_ACTIVE, NO_SERVICE_TOKEN,
        INVALID_REQUEST, TIMEOUT, CONNECTION_FAILURE, RESPONSE_TOO_LARGE
    }

    private final Kind kind;
    private final Integer httpStatus;
    private final String code;

    public ReplacementSupplierException(Kind kind, Integer httpStatus, String code) {
        super("Replacement supplier: " + kind);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public String code() { return code; }
}
