package com.csro.backend.reconfirmation;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Validated supplier contract and hotel claims, ready for later evidence handling. */
public record BookingReconfirmationResult(String bookingCode, SupplierBooking supplier, HotelOutcome hotel,
                                          EvidenceBody supplierEvidence, EvidenceBody hotelEvidence) {

    public record EvidenceBody(String rawJson, OffsetDateTime receivedAt, boolean localMock) {}

    public record SupplierBooking(String supplierBookingId, String referenceBookingId,
                                  String propertyId, String contractStatus, Stay stay,
                                  OffsetDateTime updatedAt) {}

    public record Stay(LocalDate checkInDate, LocalDate checkOutDate, int adults, int rooms) {}

    public sealed interface HotelOutcome permits HotelReply, HotelPending {}

    public record HotelReply(String reconfirmationRequestId, String reconfirmationId,
                             String status, String supplierBookingId, String propertyId,
                             List<Night> nights, OffsetDateTime occurredAt,
                             OffsetDateTime sentAt) implements HotelOutcome {
        public HotelReply {
            nights = nights == null ? null : List.copyOf(nights);
        }
    }

    public record Night(LocalDate stayDate, String reportedProvisionStatus, String message) {}

    public record HotelPending(String reconfirmationRequestId, String reconfirmationId,
                               String status, OffsetDateTime receivedAt) implements HotelOutcome {}
}
