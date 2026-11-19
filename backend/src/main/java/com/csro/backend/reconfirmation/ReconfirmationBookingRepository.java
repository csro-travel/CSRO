package com.csro.backend.reconfirmation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ReconfirmationBookingRepository {

    private final JdbcClient jdbcClient;

    ReconfirmationBookingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    Optional<Booking> findByCode(String bookingCode) {
        return jdbcClient.sql("""
                SELECT b.booking_code, b.supplier_booking_id, h.hotel_code, b.supplier_status,
                       b.check_in_date, b.check_out_date, b.adults, b.rooms, n.stay_date
                FROM bookings b
                JOIN hotels h ON h.id = b.hotel_id
                LEFT JOIN booking_nights n ON n.booking_id = b.id
                WHERE b.booking_code = :bookingCode AND b.supplier_code = 'MOCK_SUPPLIER'
                ORDER BY n.stay_date
                """)
                .param("bookingCode", bookingCode)
                .query(rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    List<LocalDate> dates = new ArrayList<>();
                    Booking booking = new Booking(rs.getString("booking_code"),
                            rs.getString("supplier_booking_id"), rs.getString("hotel_code"),
                            rs.getString("supplier_status"),
                            rs.getObject("check_in_date", LocalDate.class),
                            rs.getObject("check_out_date", LocalDate.class),
                            rs.getInt("adults"), rs.getInt("rooms"), dates);
                    do {
                        LocalDate date = rs.getObject("stay_date", LocalDate.class);
                        if (date != null) {
                            dates.add(date);
                        }
                    } while (rs.next());
                    return Optional.of(booking);
                });
    }

    record Booking(String bookingCode, String supplierBookingId, String propertyId,
                   String supplierStatus, LocalDate checkInDate, LocalDate checkOutDate,
                   int adults, int rooms, List<LocalDate> stayDates) {}
}
