package com.csro.backend.cases;

import com.csro.backend.searching.AlternativeSearchResultService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Profile("local-chat-demo")
@RestController
@RequestMapping("/api/v1/cases/{caseCode}/airbnb-demo-searches")
class CaseAirbnbDemoController {
    private final CaseAccess access;
    private final AlternativeSearchResultService results;
    private final CaseAirbnbGuideService guides;

    CaseAirbnbDemoController(CaseAccess access, AlternativeSearchResultService results, CaseAirbnbGuideService guides) {
        this.access = access;
        this.results = results;
        this.guides = guides;
    }

    @GetMapping(value = "/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> latest(HttpServletRequest request, @PathVariable String caseCode) {
        requireCounselor(request, caseCode);
        return results.snapshot(caseCode, null)
                .map(snapshot -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(snapshot))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping(value = "/{searchId}/selection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(HttpServletRequest request, @PathVariable String caseCode, @PathVariable UUID searchId,
                @RequestBody JsonNode body) {
        guides.saveSelection(request.getUserPrincipal(), caseCode, searchId, body);
    }

    @PutMapping(value = "/{searchId}/guides/{requestId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void guide(HttpServletRequest request, @PathVariable String caseCode, @PathVariable UUID searchId,
               @PathVariable UUID requestId, @RequestBody JsonNode body) {
        guides.send(request.getUserPrincipal(), caseCode, searchId, requestId, body);
    }

    @PutMapping(value = "/{requestId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void save(HttpServletRequest request, @PathVariable String caseCode, @PathVariable UUID requestId,
            @RequestBody JsonNode body) {
        requireCounselor(request, caseCode);
        results.save(caseCode, requestId, body);
    }

    private void requireCounselor(HttpServletRequest request, String caseCode) {
        if (!access.chatUser(request.getUserPrincipal(), caseCode).isCounselor()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN_ROLE");
        }
    }
}
