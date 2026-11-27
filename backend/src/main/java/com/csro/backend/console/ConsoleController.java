package com.csro.backend.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 상담사 콘솔 조회. 모두 상담사 전용 읽기이며 업무 상태를 바꾸지 않는다. */
@RestController
@RequestMapping(value = "/api/v1/console", produces = MediaType.APPLICATION_JSON_VALUE)
public class ConsoleController {
    private final ConsoleQueries queries;

    ConsoleController(ConsoleQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/overview")
    public ResponseEntity<String> overview(HttpServletRequest request) {
        return ok(queries.overview(request.getUserPrincipal()));
    }

    @GetMapping("/recheck-jobs")
    public ResponseEntity<String> recheckJobs(HttpServletRequest request) {
        return ok(queries.recheckJobs(request.getUserPrincipal()));
    }

    @GetMapping("/risk-assessments")
    public ResponseEntity<String> riskAssessments(HttpServletRequest request) {
        return ok(queries.riskAssessments(request.getUserPrincipal()));
    }

    @GetMapping("/evidence")
    public ResponseEntity<String> evidence(HttpServletRequest request) {
        return ok(queries.evidence(request.getUserPrincipal()));
    }

    @GetMapping("/hotel-reply-analyses")
    public ResponseEntity<String> hotelReplyAnalyses(HttpServletRequest request) {
        return ok(queries.hotelReplyAnalyses(request.getUserPrincipal()));
    }

    @GetMapping("/bookings")
    public ResponseEntity<String> bookings(HttpServletRequest request) {
        return ok(queries.bookings(request.getUserPrincipal()));
    }

    private static ResponseEntity<String> ok(String json) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(json);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> error(Exception failure) {
        int status = 500;
        String code = "INTERNAL_ERROR";
        if (failure instanceof ResponseStatusException typed) {
            status = typed.getStatusCode().value();
            code = typed.getReason();
        }
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setDetail(body.getTitle());
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Cache-Control", "private, no-store");
        if (status == 401) response.header("WWW-Authenticate", "Bearer");
        return response.body(body);
    }
}
