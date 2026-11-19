package com.csro.backend.reconfirmation;

/** Failure of an internal reconfirmation, with upstream metadata when a reply exists. */
public final class BookingReconfirmationException extends RuntimeException {

    public enum Step { BOOKING, API_026, API_027 }

    public enum Kind {
        BOOKING_NOT_FOUND, INVALID_BOOKING_NIGHTS, NO_SERVICE_TOKEN,
        AUTHENTICATION, NOT_FOUND, REQUEST_MISMATCH, UNAVAILABLE, HTTP_ERROR,
        INVALID_RESPONSE, TIMEOUT, CONNECTION_FAILURE
    }

    private final Step step;
    private final Kind kind;
    private final Integer httpStatus;
    private final String upstreamCode;
    private final String traceId;

    BookingReconfirmationException(Step step, Kind kind, Integer httpStatus,
                                   String upstreamCode, String traceId) {
        super(step + " " + kind + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")"));
        this.step = step;
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.upstreamCode = upstreamCode;
        this.traceId = traceId;
    }

    public Step step() { return step; }
    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public String upstreamCode() { return upstreamCode; }
    public String traceId() { return traceId; }
}
