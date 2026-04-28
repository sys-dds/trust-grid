package com.trustgrid.api.evidence;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/evidence")
    public EvidenceResponse create(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                   @Valid @RequestBody CreateEvidenceRequest request) {
        return service.create(idempotencyKey, request);
    }

    @GetMapping("/api/v1/evidence/{evidenceId}")
    public EvidenceResponse get(@PathVariable UUID evidenceId) {
        return service.get(evidenceId);
    }

    @GetMapping("/api/v1/evidence")
    public List<EvidenceResponse> search(@RequestParam(required = false) EvidenceTargetType targetType,
                                         @RequestParam(required = false) UUID targetId) {
        return service.search(targetType, targetId);
    }

    @GetMapping("/api/v1/evidence-requirements")
    public List<EvidenceRequirementResponse> requirements(@RequestParam(required = false) EvidenceTargetType targetType,
                                                         @RequestParam(required = false) UUID targetId) {
        return service.requirements(targetType, targetId);
    }

    @PostMapping("/api/v1/evidence-requirements/{requirementId}/satisfy")
    public EvidenceRequirementResponse satisfy(@PathVariable UUID requirementId,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                               @Valid @RequestBody SatisfyEvidenceRequirementRequest request) {
        return service.satisfy(requirementId, idempotencyKey, request);
    }
}
