package com.csro.backend.cases;

import java.security.Principal;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("local-chat-demo")
@ConditionalOnProperty(name = "airbnb.demo.booking.enabled", havingValue = "true")
// Web server only: in a one-shot run (migration, risk batch) the scheduler thread would keep the process alive.
@ConditionalOnWebApplication
@EnableScheduling
@RestController
@RequestMapping("/api/v1/cases/{caseId}/airbnb-demo-searches/simulations")
class LocalAlternativeBookingController {
    private static final Logger log = LoggerFactory.getLogger(LocalAlternativeBookingController.class);
    private final LocalAlternativeBooking booking;

    LocalAlternativeBookingController(LocalAlternativeBooking booking) {
        this.booking = booking;
    }

    record Booked(String bookingId) {}

    @GetMapping("/{offerId}")
    LocalAlternativeBooking.Action action(Principal principal, @PathVariable String caseId, @PathVariable UUID offerId) {
        return booking.action(principal, caseId, offerId);
    }

    @PostMapping("/{offerId}/book")
    Booked book(Principal principal, @PathVariable String caseId, @PathVariable UUID offerId) {
        return new Booked(booking.book(principal, caseId, offerId));
    }

    @Scheduled(fixedDelay = 500)
    void selectFirstOffers() {
        for (String caseId : booking.dueCases()) {
            try {
                booking.selectFirst(caseId);
            } catch (RuntimeException exception) {
                log.warn("Could not process a due local alternative selection");
            }
        }
    }
}
