package com.csro.backend.reconfirmation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.csro.backend.reconfirmation.BookingReconfirmationException.Kind;
import com.csro.backend.reconfirmation.BookingReconfirmationException.Step;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.EvidenceBody;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelPending;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Stay;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.SupplierBooking;
import com.csro.backend.reconfirmation.ReconfirmationBookingRepository.Booking;
import com.csro.backend.reconfirmation.ReconfirmationMockClient.HotelRequest;
import com.csro.backend.reconfirmation.ReconfirmationMockClient.Received;

class ReconfirmationScopeTest {

    private static final LocalDate IN = LocalDate.of(2026, 11, 10);
    private static final LocalDate OUT = IN.plusDays(3);
    private static final List<LocalDate> DATES = IN.datesUntil(OUT).toList();
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-09T10:00:00+09:00");
    private static final Booking BOOKING = booking("YB-001", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED",
            IN, OUT, 2, 1, DATES);
    private static final ReconfirmationScope SCOPE = scope(DATES);

    @ParameterizedTest
    @MethodSource("changedBookings")
    void rejectsChangedFrozenScopeBeforeAnyExternalRequest(Booking current) {
        var bookings = mock(ReconfirmationBookingRepository.class);
        var mockClient = mock(ReconfirmationMockClient.class);
        when(bookings.findByCode("YB-001")).thenReturn(Optional.of(current));
        var service = new BookingReconfirmationService(bookings, mockClient);

        var failure = assertThrows(BookingReconfirmationException.class,
                () -> service.reconfirm("YB-001", "stable-job-request", SCOPE));

        assertEquals(Step.BOOKING, failure.step());
        assertEquals(Kind.REQUEST_MISMATCH, failure.kind());
        assertFalse(failure.getMessage().contains("SUP-CHANGED"));
        verifyNoInteractions(mockClient);
    }

    static Stream<Booking> changedBookings() {
        return Stream.of(
                booking("YB-CHANGED", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED", IN, OUT, 2, 1, DATES),
                booking("YB-001", "SUP-CHANGED", "HOTEL-001", "CONFIRMED", IN, OUT, 2, 1, DATES),
                booking("YB-001", "SUP-BOOK-001", "HOTEL-CHANGED", "CONFIRMED", IN, OUT, 2, 1, DATES),
                booking("YB-001", "SUP-BOOK-001", "HOTEL-001", "CANCELLED", IN, OUT, 2, 1, DATES),
                booking("YB-001", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED", IN.minusDays(1), OUT,
                        2, 1, IN.minusDays(1).datesUntil(OUT).toList()),
                booking("YB-001", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED", IN, OUT.plusDays(1),
                        2, 1, IN.datesUntil(OUT.plusDays(1)).toList()),
                booking("YB-001", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED", IN, OUT, 3, 1, DATES),
                booking("YB-001", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED", IN, OUT, 2, 2, DATES));
    }

    @Test
    void rejectsIncompleteCurrentNightsWithoutExternalRequests() {
        var bookings = mock(ReconfirmationBookingRepository.class);
        var mockClient = mock(ReconfirmationMockClient.class);
        when(bookings.findByCode("YB-001")).thenReturn(Optional.of(booking("YB-001", "SUP-BOOK-001",
                "HOTEL-001", "CONFIRMED", IN, OUT, 2, 1, DATES.subList(0, 2))));
        var service = new BookingReconfirmationService(bookings, mockClient);

        var failure = assertThrows(BookingReconfirmationException.class,
                () -> service.reconfirm("YB-001", "stable-job-request", SCOPE));

        assertEquals(Kind.INVALID_BOOKING_NIGHTS, failure.kind());
        verifyNoInteractions(mockClient);
    }

    @Test
    void preservesOriginalAndFrozenCallsAndTheirExactOutboundRequest() {
        var bookings = mock(ReconfirmationBookingRepository.class);
        var mockClient = mock(ReconfirmationMockClient.class);
        when(bookings.findByCode("YB-001")).thenReturn(Optional.of(BOOKING));
        var supplier = new SupplierBooking("SUP-BOOK-001", "YB-001", "HOTEL-001", "CONFIRMED",
                new Stay(IN, OUT, 2, 1), AT);
        var evidence = new EvidenceBody("{}", AT, true, null);
        when(mockClient.getSupplierBooking("SUP-BOOK-001")).thenReturn(new Received<>(supplier, evidence));
        HotelRequest expected = new HotelRequest("stable-job-request", "SUP-BOOK-001", "HOTEL-001",
                DATES.stream().map(LocalDate::toString).toList());
        var pending = new HotelPending("stable-job-request", "RC-001", "PENDING", AT);
        when(mockClient.reconfirm(expected)).thenReturn(new Received<>(pending, evidence));
        var service = new BookingReconfirmationService(bookings, mockClient);

        assertSame(pending, service.reconfirm("YB-001", "stable-job-request").hotel());
        assertSame(pending, service.reconfirm("YB-001", "stable-job-request", SCOPE).hotel());

        verify(mockClient, org.mockito.Mockito.times(2)).getSupplierBooking("SUP-BOOK-001");
        verify(mockClient, org.mockito.Mockito.times(2)).reconfirm(expected);
        verifyNoMoreInteractions(mockClient);
    }

    @Test
    void preservesLegacyEvidenceDelegationAndUsesFrozenOverloadForJobs() {
        var reconfirmation = mock(BookingReconfirmationService.class);
        var bookings = mock(ReconfirmationBookingRepository.class);
        var jdbc = mock(JdbcClient.class);
        var manager = mock(PlatformTransactionManager.class);
        var result = new BookingReconfirmationResult("YB-001", null,
                new HotelPending("stable-job-request", "RC-001", "PENDING", AT), null, null);
        when(reconfirmation.reconfirm("YB-001", "legacy-request")).thenReturn(result);
        when(reconfirmation.reconfirm("YB-001", "stable-job-request", SCOPE)).thenReturn(result);
        var evidence = new BookingReconfirmationEvidenceService(reconfirmation, bookings, jdbc, manager);

        assertSame(result, evidence.reconfirmAndApply("YB-001", "legacy-request").result());
        assertSame(result, evidence.reconfirmAndApply("YB-001", "stable-job-request", SCOPE, 7L, ignored -> fail("Pending reply cannot complete a job")).result());

        verify(reconfirmation).reconfirm("YB-001", "legacy-request");
        verify(reconfirmation).reconfirm("YB-001", "stable-job-request", SCOPE);
        verifyNoMoreInteractions(reconfirmation);
        verifyNoInteractions(bookings, jdbc, manager);
    }

    @Test
    void freezesTheDatesAndRejectsMissingRepeatedAndOutOfOrderNights() {
        var mutable = new ArrayList<>(DATES);
        var frozen = scope(mutable);
        mutable.removeFirst();
        assertTrue(frozen.matches(BOOKING));
        assertThrows(UnsupportedOperationException.class, () -> frozen.stayDates().clear());
        assertThrows(IllegalArgumentException.class, () -> scope(DATES.subList(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> scope(List.of(IN, IN, IN.plusDays(2))));
        assertThrows(IllegalArgumentException.class, () -> scope(List.of(IN.plusDays(1), IN, IN.plusDays(2))));
        assertThrows(IllegalArgumentException.class, () -> new ReconfirmationScope("YB-001", "SUP-BOOK-001",
                "HOTEL-001", "CANCELLED", IN, OUT, 2, 1, DATES));
    }

    private static ReconfirmationScope scope(List<LocalDate> dates) {
        return new ReconfirmationScope("YB-001", "SUP-BOOK-001", "HOTEL-001", "CONFIRMED", IN, OUT, 2, 1, dates);
    }

    private static Booking booking(String code, String supplier, String property, String status,
                                   LocalDate in, LocalDate out, int adults, int rooms, List<LocalDate> dates) {
        return new Booking(code, supplier, property, status, in, out, adults, rooms, dates);
    }
}
