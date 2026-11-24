package com.csro.backend.reconfirmation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Test-only package bridge to the existing package-private booking/HTTP adapters. */
public final class RiskEvidenceTestFactory {
    private RiskEvidenceTestFactory() {}

    public static BookingReconfirmationEvidenceService validatedReply(
            JdbcClient jdbc, PlatformTransactionManager manager, BookingReconfirmationResult result) {
        var upstream = mock(BookingReconfirmationService.class);
        when(upstream.reconfirm(anyString(), anyString(), any(ReconfirmationScope.class))).thenReturn(result);
        return new BookingReconfirmationEvidenceService(upstream, new ReconfirmationBookingRepository(jdbc),
                jdbc, manager);
    }

    public static FrozenBoundary frozenBoundary(JdbcClient jdbc, PlatformTransactionManager manager) {
        var http = mock(ReconfirmationMockClient.class);
        var bookings = new ReconfirmationBookingRepository(jdbc);
        var upstream = new BookingReconfirmationService(bookings, http);
        return new FrozenBoundary(new BookingReconfirmationEvidenceService(upstream, bookings, jdbc, manager),
                () -> verifyNoInteractions(http));
    }

    public record FrozenBoundary(BookingReconfirmationEvidenceService service, Runnable assertNoHttp) {}
}
