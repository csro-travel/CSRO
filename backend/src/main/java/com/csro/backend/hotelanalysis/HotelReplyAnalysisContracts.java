package com.csro.backend.hotelanalysis;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** API-024 wire values describe hotel claims; they never authorize business fact changes. */
public final class HotelReplyAnalysisContracts {
    private HotelReplyAnalysisContracts() {}

    public record Input(String evidenceId, String bookingId, String hotelId, List<LocalDate> stayDates,
                        String originalMessage, String locale, OffsetDateTime messageOccurredAt,
                        String hotelTimeZone) {
        public Input {
            // Preserve malformed null values for the client's stable validation error, not an incidental NPE.
            stayDates = stayDates == null ? null : Collections.unmodifiableList(new ArrayList<>(stayDates));
        }
    }

    public record Claim(LocalDate stayDate, String reportedProvisionStatus, String supportingText) {}

    /** Snapshots are the actual transmitted request and received JSON, without authentication headers. */
    public record Analysis(String evidenceId, String analysisStatus, String modelVersion, List<Claim> claims,
                           List<LocalDate> unresolvedStayDates, boolean requiresHumanVerification,
                           List<String> warnings, String requestJson, String responseJson) {
        public Analysis {
            claims = claims == null ? null : List.copyOf(claims);
            unresolvedStayDates = unresolvedStayDates == null ? null : List.copyOf(unresolvedStayDates);
            warnings = warnings == null ? null : List.copyOf(warnings);
        }
    }
}
