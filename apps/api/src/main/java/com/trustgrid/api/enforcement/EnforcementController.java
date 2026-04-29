package com.trustgrid.api.enforcement;

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
public class EnforcementController {
    private final TrustSafetyService service;

    public EnforcementController(TrustSafetyService service) { this.service = service; }

    @PostMapping("/api/v1/enforcement/policies")
    Map<String, Object> policy(@RequestBody Map<String, Object> request) { return service.createEnforcementPolicy(request); }

    @GetMapping("/api/v1/enforcement/policies")
    List<Map<String, Object>> policies() { return service.enforcementPolicies(); }

    @PostMapping("/api/v1/enforcement/simulate")
    Map<String, Object> simulate(@RequestBody Map<String, Object> request) { return service.simulateEnforcement(request); }

    @PostMapping("/api/v1/enforcement/actions")
    Map<String, Object> execute(@RequestBody Map<String, Object> request) { return service.executeEnforcement(request); }

    @PostMapping("/api/v1/enforcement/actions/{actionId}/reverse")
    Map<String, Object> reverse(@PathVariable UUID actionId, @RequestBody Map<String, Object> request) {
        return service.reverseEnforcement(actionId, request);
    }

    @GetMapping("/api/v1/enforcement/actions")
    List<Map<String, Object>> actions() { return service.enforcementActions(); }

    @GetMapping("/api/v1/participants/{participantId}/enforcement-timeline")
    List<Map<String, Object>> timeline(@PathVariable UUID participantId) { return service.enforcementTimeline(participantId); }
}
