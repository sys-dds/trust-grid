package com.trustgrid.api.policy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrustPolicyController {
    private final TrustPolicyService policyService;
    private final PolicySimulationService simulationService;

    public TrustPolicyController(TrustPolicyService policyService, PolicySimulationService simulationService) {
        this.policyService = policyService;
        this.simulationService = simulationService;
    }

    @PostMapping("/api/v1/policies")
    public Map<String, Object> create(@RequestBody Map<String, Object> request) {
        return policyService.create(request);
    }

    @PostMapping("/api/v1/policies/{policyId}/activate")
    public Map<String, Object> activate(@PathVariable UUID policyId) {
        return policyService.activate(policyId);
    }

    @PostMapping("/api/v1/policies/{policyId}/retire")
    public Map<String, Object> retire(@PathVariable UUID policyId) {
        return policyService.retire(policyId);
    }

    @GetMapping("/api/v1/policies")
    public List<Map<String, Object>> policies() {
        return policyService.policies();
    }

    @GetMapping("/api/v1/policies/active")
    public List<Map<String, Object>> active() {
        return policyService.active();
    }

    @PostMapping("/api/v1/policy-simulations/trust")
    public Map<String, Object> trust(@RequestBody Map<String, Object> request) {
        return simulationService.simulate("TRUST_POLICY", request);
    }

    @PostMapping("/api/v1/policy-simulations/shadow-risk")
    public Map<String, Object> shadowRisk(@RequestBody Map<String, Object> request) {
        return simulationService.simulate("SHADOW_RISK", request);
    }

    @PostMapping("/api/v1/policy-simulations/counterfactual-ranking")
    public Map<String, Object> ranking(@RequestBody Map<String, Object> request) {
        return simulationService.simulate("COUNTERFACTUAL_RANKING", request);
    }

    @PostMapping("/api/v1/policy-simulations/dispute-decision")
    public Map<String, Object> dispute(@RequestBody Map<String, Object> request) {
        return simulationService.simulate("DISPUTE_DECISION", request);
    }

    @GetMapping("/api/v1/abuse-campaigns")
    public List<Map<String, Object>> campaigns() {
        return policyService.campaigns();
    }

    @GetMapping("/api/v1/data-retention/summary")
    public Map<String, Object> retention() {
        return policyService.retention();
    }
}
