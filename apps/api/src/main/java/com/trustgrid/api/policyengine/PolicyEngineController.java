package com.trustgrid.api.policyengine;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PolicyEngineController {
    private final PolicyEngineService service;

    public PolicyEngineController(PolicyEngineService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/policy-engine/rules")
    public PolicyRuleResponse createRule(@Valid @RequestBody CreatePolicyRuleRequest request) {
        return service.createRule(request);
    }

    @GetMapping("/api/v1/policy-engine/rules")
    public List<PolicyRuleResponse> rules(@RequestParam(required = false) String policyName,
                                          @RequestParam(required = false) String policyVersion,
                                          @RequestParam(required = false) String ruleType) {
        return service.rules(policyName, policyVersion, ruleType);
    }

    @PostMapping("/api/v1/policy-engine/evaluate")
    public PolicyDecisionResponse evaluate(@Valid @RequestBody EvaluatePolicyRequest request) {
        return service.evaluate(request);
    }

    @GetMapping("/api/v1/policy-engine/decisions/{decisionId}")
    public PolicyDecisionResponse decision(@PathVariable UUID decisionId) {
        return service.decision(decisionId);
    }

    @GetMapping("/api/v1/policy-engine/decisions")
    public List<PolicyDecisionResponse> decisions(@RequestParam(required = false) String targetType,
                                                  @RequestParam(required = false) UUID targetId) {
        return service.decisions(targetType, targetId);
    }

    @GetMapping("/api/v1/policy-engine/decisions/{decisionId}/explanation")
    public PolicyDecisionResponse decisionExplanation(@PathVariable UUID decisionId) {
        return service.decision(decisionId);
    }

    @GetMapping("/api/v1/policy-engine/explain")
    public PolicyDecisionResponse explain(@RequestParam String targetType,
                                          @RequestParam UUID targetId,
                                          @RequestParam(required = false) String policyName,
                                          @RequestParam(required = false) String policyVersion) {
        return service.explain(targetType, targetId, policyName, policyVersion);
    }
}
