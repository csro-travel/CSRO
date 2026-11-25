package com.csro.backend.reconfirmation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Test-only bridge to production reconfirmation HTTP/evidence adapters; no provider doubles. */
public final class IncidentEvidenceTestFactory {
    private IncidentEvidenceTestFactory() {}

    public static BookingReconfirmationEvidenceService http(JdbcClient jdbc,PlatformTransactionManager manager,
                                                             String baseUrl,String token) {
        var bookings=new ReconfirmationBookingRepository(jdbc);
        var upstream=new BookingReconfirmationService(bookings,new ReconfirmationMockClient(baseUrl,token));
        return new BookingReconfirmationEvidenceService(upstream,bookings,jdbc,manager);
    }
}
