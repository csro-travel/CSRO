package com.csro.backend.searching;

import com.csro.backend.searching.AlternativeSearchResultRepository.CaseStay;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class AlternativeSearchResultService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AlternativeSearchResultRepository repository;
    public AlternativeSearchResultService(AlternativeSearchResultRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<String> snapshot(String caseCode, UUID searchId) {
        return repository.findSnapshot(caseCode, searchId == null ? "" : "LOCAL-ALT-" + searchId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void save(String caseCode, UUID requestId, JsonNode body) {
        CaseStay stay = repository.findCaseStay(caseCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
        if ("CLOSED".equals(stay.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CASE_CLOSED");
        }
        String bookingCode = stay.bookingCode();
        JsonNode parameters = body.path("parameters");
        JsonNode result = body.path("result");
        if (!caseCode.equals(identifier(parameters, "caseId"))
                || !caseCode.equals(identifier(result, "case_id"))
                || !bookingCode.equals(identifier(parameters, "bookingId"))
                || !bookingCode.equals(identifier(result, "booking_id"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Search result does not match request");
        }
        validateStay(stay, parameters, result.path("policy"));
        JsonNode recommendations = result.path("top_recommendations");
        if (!recommendations.isArray() || recommendations.size() > 5
                || !result.path("exception_candidates").isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid search result");
        }
        String jobCode = "LOCAL-ALT-" + requestId;
        String input = parameters.toString();
        String payload = result.toString();
        if (repository.insertCompleted(jobCode, caseCode, bookingCode, input, payload) == 0
                && !repository.matchesCompleted(jobCode, input, payload)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case, booking or search result does not match");
        }
        if (!recommendations.isEmpty()) {
            if (!result.path("ranking_input").isArray()
                    || result.path("ranking_input").size() < recommendations.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ranking input");
            }
            String version = requiredText(result, "ranking_version", 80);
            String analyzedAt = requiredText(result, "analyzed_at", 40);
            try {
                OffsetDateTime.parse(analyzedAt);
            } catch (DateTimeParseException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid analyzed_at", exception);
            }
            repository.insertDemoRanking(jobCode, version, analyzedAt);
        }
    }

    private static void validateStay(CaseStay stay, JsonNode parameters, JsonNode policy) {
        int nights = Math.toIntExact(ChronoUnit.DAYS.between(stay.checkInDate(), stay.checkOutDate()));
        if (stay.checkInDate().isBefore(LocalDate.now(SEOUL))
                || !stay.checkInDate().toString().equals(parameters.path("checkInDate").asText())
                || !stay.checkOutDate().toString().equals(parameters.path("checkOutDate").asText())
                || !stay.checkInDate().toString().equals(policy.path("check_in_date").asText())
                || !stay.checkOutDate().toString().equals(policy.path("check_out_date").asText())
                || !matchesCount(parameters.path("nights"), nights)
                || !matchesCount(parameters.path("adults"), stay.adults())
                || !matchesCount(policy.path("nights"), nights)
                || !matchesCount(policy.path("adults"), stay.adults())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Search stay does not match the booking");
        }
    }

    private static boolean matchesCount(JsonNode value, int count) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() == count;
    }

    private static String identifier(JsonNode payload, String field) {
        return requiredText(payload, field, 40);
    }

    private static String requiredText(JsonNode payload, String field, int maxLength) {
        JsonNode value = payload.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
        return value.asText();
    }

}
