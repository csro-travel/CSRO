package com.csro.backend.reconfirmation;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.csro.backend.reconfirmation.BookingReconfirmationException.Kind;
import com.csro.backend.reconfirmation.BookingReconfirmationException.Step;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelOutcome;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Night;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Stay;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.SupplierBooking;
import com.csro.backend.reconfirmation.ReconfirmationBookingRepository.Booking;
import com.csro.backend.reconfirmation.ReconfirmationMockClient.HotelRequest;

@Service
public class BookingReconfirmationService {

    private static final Set<String> PROVISION_STATUSES = Set.of("AVAILABLE", "UNAVAILABLE", "UNKNOWN");

    private final ReconfirmationBookingRepository bookings;
    private final ReconfirmationMockClient mock;

    public BookingReconfirmationService(ReconfirmationBookingRepository bookings, ReconfirmationMockClient mock) {
        this.bookings = bookings;
        this.mock = mock;
    }

    /** Performs an internal read and external HTTP validation without applying the claims. */
    @Transactional(propagation = Propagation.NEVER)
    public BookingReconfirmationResult reconfirm(String bookingCode, String requestId) {
        Assert.hasText(bookingCode, "bookingCode is required");
        Assert.hasText(requestId, "reconfirmationRequestId is required");

        Booking booking = bookings.findByCode(bookingCode).orElseThrow(() ->
                new BookingReconfirmationException(Step.BOOKING, Kind.BOOKING_NOT_FOUND, null, null, null));
        validateBookingNights(booking);
        var supplierResponse = mock.getSupplierBooking(booking.supplierBookingId());
        SupplierBooking supplier = supplierResponse.body();
        validateSupplier(booking, supplier);

        HotelRequest request = new HotelRequest(requestId, booking.supplierBookingId(), booking.propertyId(),
                booking.stayDates().stream().map(LocalDate::toString).toList());
        var hotelResponse = mock.reconfirm(request);
        HotelOutcome outcome = hotelResponse.body();
        switch (outcome) {
            case HotelReply reply -> validateReply(booking, requestId, reply);
            case HotelPending pending -> {
                if (!"PENDING".equals(pending.status()) || !requestId.equals(pending.reconfirmationRequestId())
                        || !StringUtils.hasText(pending.reconfirmationId()) || pending.receivedAt() == null) {
                    throw invalidReply(Step.API_027, 202);
                }
            }
        }
        return new BookingReconfirmationResult(bookingCode, supplier, outcome,
                supplierResponse.evidence(), hotelResponse.evidence());
    }

    static void validateBookingNights(Booking booking) {
        List<LocalDate> dates = booking.stayDates();
        LocalDate expected = booking.checkInDate();
        for (LocalDate date : dates) {
            if (!expected.equals(date) || !date.isBefore(booking.checkOutDate())) {
                throw invalidBookingNights();
            }
            expected = expected.plusDays(1);
        }
        if (dates.isEmpty() || !expected.equals(booking.checkOutDate())) {
            throw invalidBookingNights();
        }
    }

    static void validateSupplier(Booking booking, SupplierBooking supplier) {
        Stay stay = supplier.stay();
        if (!booking.supplierBookingId().equals(supplier.supplierBookingId())
                || !booking.bookingCode().equals(supplier.referenceBookingId())
                || !booking.propertyId().equals(supplier.propertyId())
                || !"CONFIRMED".equals(supplier.contractStatus())
                || !booking.supplierStatus().equals(supplier.contractStatus())
                || stay == null || !booking.checkInDate().equals(stay.checkInDate())
                || !booking.checkOutDate().equals(stay.checkOutDate())
                || booking.adults() != stay.adults() || booking.rooms() != stay.rooms()
                || supplier.updatedAt() == null) {
            throw invalidReply(Step.API_026, 200);
        }
    }

    static void validateReply(Booking booking, String requestId, HotelReply reply) {
        if (!"RESPONDED".equals(reply.status()) || !requestId.equals(reply.reconfirmationRequestId())
                || !booking.supplierBookingId().equals(reply.supplierBookingId())
                || !booking.propertyId().equals(reply.propertyId())
                || !StringUtils.hasText(reply.reconfirmationId()) || reply.nights() == null
                || reply.occurredAt() == null || reply.sentAt() == null) {
            throw invalidReply(Step.API_027, 200);
        }

        Set<LocalDate> remaining = new HashSet<>(booking.stayDates());
        for (Night night : reply.nights()) {
            // Removing a date fails for an out-of-range or repeated date.
            if (!remaining.remove(night.stayDate()) || night.reportedProvisionStatus() == null
                    || !PROVISION_STATUSES.contains(night.reportedProvisionStatus())
                    || night.message() == null) {
                throw invalidReply(Step.API_027, 200);
            }
        }
        if (!remaining.isEmpty()) {
            throw invalidReply(Step.API_027, 200);
        }
    }

    private static BookingReconfirmationException invalidBookingNights() {
        return new BookingReconfirmationException(Step.BOOKING, Kind.INVALID_BOOKING_NIGHTS, null, null, null);
    }

    private static BookingReconfirmationException invalidReply(Step step, int status) {
        return new BookingReconfirmationException(step, Kind.INVALID_RESPONSE, status, null, null);
    }
}
