package com.trustgrid.api.evidencecustody;

import com.trustgrid.api.trustsafety.TrustSafetyService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvidenceCustodyController {
    private final TrustSafetyService service;

    public EvidenceCustodyController(TrustSafetyService service) { this.service = service; }

    @PostMapping("/api/v1/evidence/{evidenceId}/versions")
    Map<String, Object> version(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.createEvidenceVersion(evidenceId, request);
    }

    @GetMapping("/api/v1/evidence/{evidenceId}/versions")
    List<Map<String, Object>> versions(@PathVariable UUID evidenceId) { return service.evidenceVersions(evidenceId); }

    @GetMapping("/api/v1/evidence/{evidenceId}/custody-chain")
    List<Map<String, Object>> chain(@PathVariable UUID evidenceId) { return service.custodyChain(evidenceId); }

    @PostMapping("/api/v1/evidence/{evidenceId}/custody-events")
    Map<String, Object> custody(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.createCustodyEvent(evidenceId, request);
    }

    @PostMapping("/api/v1/evidence/{evidenceId}/tamper-check")
    Map<String, Object> tamper(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.tamperCheck(evidenceId, request);
    }

    @PostMapping("/api/v1/evidence/{evidenceId}/access-simulate")
    Map<String, Object> access(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.evidenceAccess(evidenceId, request);
    }

    @PostMapping("/api/v1/evidence/{evidenceId}/retention")
    Map<String, Object> retention(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.retention(evidenceId, request);
    }

    @PostMapping("/api/v1/evidence/{evidenceId}/legal-hold")
    Map<String, Object> hold(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.legalHold(evidenceId, request);
    }

    @PostMapping("/api/v1/evidence/{evidenceId}/consistency-replay")
    Map<String, Object> replay(@PathVariable UUID evidenceId, @RequestBody Map<String, Object> request) {
        return service.evidenceReplay(evidenceId, request);
    }

    @PostMapping("/api/v1/evidence/disclosure-bundles")
    Map<String, Object> bundle(@RequestBody Map<String, Object> request) { return service.disclosureBundle(request); }
}
