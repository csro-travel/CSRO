package com.csro.backend.cases;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/cases/{caseId}/postprocessing")
class CasePostprocessingController {
    private final CasePostprocessingService service;

    CasePostprocessingController(CasePostprocessingService service) {
        this.service = service;
    }

    record DraftRequest(UUID requestId) {}

    @GetMapping
    ResponseEntity<CasePostprocessingService.Draft> latest(Principal principal, @PathVariable String caseId) {
        return service.latest(principal, caseId)
                .map(draft -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(draft))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @PostMapping("/drafts")
    ResponseEntity<CasePostprocessingService.Draft> draft(Principal principal, @PathVariable String caseId,
                                                         @RequestBody DraftRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.draft(principal, caseId, request.requestId()));
    }

    @PostMapping("/drafts/{draftId}/finalize")
    ResponseEntity<CasePostprocessingService.Finalized> finalizeDraft(
            Principal principal, @PathVariable String caseId, @PathVariable UUID draftId,
            @RequestBody CasePostprocessingService.Submission request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.finalizeDraft(principal, caseId, draftId, request));
    }

    @GetMapping("/drafts/{draftId}/document")
    ResponseEntity<byte[]> download(Principal principal, @PathVariable String caseId, @PathVariable UUID draftId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(caseId + "-postprocessing.docx").build().toString())
                .contentType(CasePostprocessingService.DOCX)
                .body(service.download(principal, caseId, draftId));
    }

    @ExceptionHandler({ResponseStatusException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> problem(Exception error) {
        var status = error instanceof ResponseStatusException ex ? ex.getStatusCode() : HttpStatus.BAD_REQUEST;
        var code = error instanceof ResponseStatusException ex ? ex.getReason() : "INVALID_POSTPROCESSING_REQUEST";
        var body = ProblemDetail.forStatus(status);
        body.setDetail(switch (code) {
            case "CASE_CLOSED" -> "종료된 사건에는 문서나 새 업무를 등록할 수 없습니다.";
            case "POSTPROCESSING_ALREADY_CHECKED_IN" -> "이미 입실이 확인되었습니다. 입실 확인 업무 선택을 해제해 주세요.";
            case "POSTPROCESSING_REPLACEMENT_NOT_CONFIRMED" -> "확정된 대체 예약이 없습니다. 입실 확인 업무 선택을 해제하거나 대체 예약을 먼저 확정해 주세요.";
            case "POSTPROCESSING_CONFLICT" -> "이미 다른 내용으로 문서가 생성되었습니다. 저장된 문서를 확인해 주세요.";
            case "POSTPROCESSING_AGENT_UNAVAILABLE" -> "초안 또는 문서 생성에 실패했습니다. 잠시 후 다시 시도해 주세요.";
            case null, default -> body.getTitle();
        });
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        var response = ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore());
        if (status.value() == 401) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(body);
    }
}
