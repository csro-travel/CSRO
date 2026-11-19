package com.csro.backend.reconfirmation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.csro.backend.reconfirmation.BookingReconfirmationResult.HotelReply;
import com.csro.backend.reconfirmation.BookingReconfirmationResult.Night;

@EnabledIfEnvironmentVariable(named = "BOOKING_RECONFIRMATION_LIVE_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.flyway.enabled=false")
@ActiveProfiles("local")
class BookingReconfirmationLiveTest {

    @Autowired
    BookingReconfirmationService service;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void readsExistingBookingFromReadOnlyPostgresAndReconfirmsOverHttp() {
        assertEquals("on", jdbcClient.sql("SHOW default_transaction_read_only").query(String.class).single());

        BookingReconfirmationResult result = service.reconfirm("YB-001", "RECHECK-001");
        assertEquals("YB-001", result.bookingCode());
        assertEquals("SUP-BOOK-001", result.supplier().supplierBookingId());
        assertEquals("HOTEL-001", result.supplier().propertyId());
        assertEquals("CONFIRMED", result.supplier().contractStatus());
        HotelReply hotel = assertInstanceOf(HotelReply.class, result.hotel());
        assertEquals("RC-001", hotel.reconfirmationId());
        assertEquals(List.of("UNAVAILABLE", "UNKNOWN", "UNKNOWN"),
                hotel.nights().stream().map(Night::reportedProvisionStatus).toList());
        assertEquals(LocalDate.parse("2026-11-10").datesUntil(LocalDate.parse("2026-11-13")).toList(),
                hotel.nights().stream().map(Night::stayDate).toList());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        assertThrows(IllegalTransactionStateException.class, () -> transaction.executeWithoutResult(
                start -> service.reconfirm("YB-001", "RECHECK-TRANSACTION")));
    }
}
