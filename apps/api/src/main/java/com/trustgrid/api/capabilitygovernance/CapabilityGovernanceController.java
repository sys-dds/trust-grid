package com.trustgrid.api.capabilitygovernance;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CapabilityGovernanceController {
    private final CapabilityGovernanceService service;

    public CapabilityGovernanceController(CapabilityGovernanceService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/capability-governance/policies")
    public Map<String, Object> createPolicy(@RequestBody Map<String, Object> request) {
        return service.createPolicy(request);
    }

    @GetMapping("/api/v1/capability-governance/policies")
    public List<Map<String, Object>> policies() {
        return service.policies();
    }

    @GetMapping("/api/v1/capability-governance/policies/{policyId}")
    public Map<String, Object> policy(@PathVariable UUID policyId) {
        return service.policy(policyId);
    }

    @PostMapping("/api/v1/capability-governance/simulate")
    public Map<String, Object> simulate(@RequestBody Map<String, Object> request) {
        return service.simulate(request);
    }

    @PostMapping("/api/v1/capability-governance/temporary-grants")
    public Map<String, Object> createTemporaryGrant(@RequestBody Map<String, Object> request) {
        return service.createTemporaryGrant(request);
    }

    @PostMapping("/api/v1/capability-governance/temporary-grants/{grantId}/revoke")
    public Map<String, Object> revokeTemporaryGrant(@PathVariable UUID grantId, @RequestBody Map<String, Object> request) {
        return service.revokeTemporaryGrant(grantId, request);
    }

    @PostMapping("/api/v1/capability-governance/temporary-grants/expire")
    public Map<String, Object> expireTemporaryGrants() {
        return service.expireTemporaryGrants();
    }

    @GetMapping("/api/v1/capability-governance/temporary-grants")
    public List<Map<String, Object>> temporaryGrants() {
        return service.temporaryGrants();
    }

    @PostMapping("/api/v1/capability-governance/break-glass")
    public Map<String, Object> createBreakGlass(@RequestBody Map<String, Object> request) {
        return service.createBreakGlass(request);
    }

    @PostMapping("/api/v1/capability-governance/break-glass/{breakGlassId}/revoke")
    public Map<String, Object> revokeBreakGlass(@PathVariable UUID breakGlassId, @RequestBody Map<String, Object> request) {
        return service.revokeBreakGlass(breakGlassId, request);
    }

    @PostMapping("/api/v1/capability-governance/break-glass/expire")
    public Map<String, Object> expireBreakGlass() {
        return service.expireBreakGlass();
    }

    @GetMapping("/api/v1/capability-governance/break-glass")
    public List<Map<String, Object>> breakGlassActions() {
        return service.breakGlassActions();
    }

    @GetMapping("/api/v1/participants/{participantId}/capability-governance-timeline")
    public List<Map<String, Object>> participantTimeline(@PathVariable UUID participantId) {
        return service.timeline(participantId);
    }

    @GetMapping("/api/v1/capability-governance/timeline")
    public List<Map<String, Object>> timeline() {
        return service.timeline(null);
    }
}
