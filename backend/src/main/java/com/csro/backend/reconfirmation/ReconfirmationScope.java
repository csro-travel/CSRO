package com.csro.backend.reconfirmation;

import java.time.LocalDate;
import java.util.List;

import org.springframework.util.StringUtils;

import com.csro.backend.reconfirmation.ReconfirmationBookingRepository.Booking;

/** Immutable booking fields frozen when an internal reconfirmation job is created. */
public record ReconfirmationScope(String bookingCode, String supplierBookingId, String propertyId,
                                 String supplierStatus, LocalDate checkInDate, LocalDate checkOutDate,
                                 int adults, int rooms, List<LocalDate> stayDates) {

    public ReconfirmationScope {
        if (!StringUtils.hasText(bookingCode) || !StringUtils.hasText(supplierBookingId)
                || !StringUtils.hasText(propertyId) || !"CONFIRMED".equals(supplierStatus)
                || checkInDate == null || checkOutDate == null || !checkInDate.isBefore(checkOutDate)
                || adults < 1 || rooms < 1 || stayDates == null || stayDates.isEmpty()) {
            throw invalidScope();
        }
        LocalDate expected = checkInDate;
        for (LocalDate date : stayDates) {
            if (!expected.equals(date) || !expected.isBefore(checkOutDate)) {
                throw invalidScope();
            }
            expected = expected.plusDays(1);
        }
        if (!expected.equals(checkOutDate)) {
            throw invalidScope();
        }
        stayDates = List.copyOf(stayDates);
    }

    boolean matches(Booking booking) {
        return booking != null && bookingCode.equals(booking.bookingCode())
                && supplierBookingId.equals(booking.supplierBookingId())
                && propertyId.equals(booking.propertyId()) && supplierStatus.equals(booking.supplierStatus())
                && checkInDate.equals(booking.checkInDate()) && checkOutDate.equals(booking.checkOutDate())
                && adults == booking.adults() && rooms == booking.rooms() && stayDates.equals(booking.stayDates());
    }

    private static IllegalArgumentException invalidScope() {
        return new IllegalArgumentException("Invalid frozen reconfirmation scope");
    }
}
