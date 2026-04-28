package com.trustgrid.api.policyengine;

import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyEngineService {
    private final PolicyRuleRepository repository;
    private final PolicyRuleEvaluator evaluator;
    private final OutboxRepository outboxRepository;

    public PolicyEngineService(PolicyRuleRepository repository, PolicyRuleEvaluator evaluator,
                               OutboxRepository outboxRepository) {
        this.repository = repository;
        this.evaluator = evaluator;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public PolicyRuleResponse createRule(CreatePolicyRuleRequest request) {
        UUID id = repository.createRule(request);
        outboxRepository.insert("POLICY", id, null, "POLICY_RULE_CREATED", Map.of("ruleKey", request.ruleKey()));
        return repository.rules(request.policyName(), request.policyVersion(), null).stream()
                .filter(rule -> rule.ruleId().equals(id))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Policy rule not found"));
    }

    public List<PolicyRuleResponse> rules(String policyName, String policyVersion, String ruleType) {
        return repository.rules(policyName, policyVersion, ruleType);
    }

    @Transactional
    public PolicyDecisionResponse evaluate(EvaluatePolicyRequest request) {
        Map<String, Object> input = new LinkedHashMap<>(repository.targetSnapshot(request.targetType(), request.targetId()));
        if (request.input() != null) {
            input.putAll(request.input());
        }

        List<Map<String, Object>> matched = new ArrayList<>();
        String decision = "ALLOW";
        for (PolicyRuleResponse rule : repository.evaluationRules(request.policyName(), request.policyVersion(), request.targetType())) {
            if (evaluator.matches(rule, input)) {
                String ruleDecision = evaluator.decision(rule);
                matched.add(Map.of(
                        "ruleId", rule.ruleId(),
                        "ruleKey", rule.ruleKey(),
                        "ruleType", rule.ruleType(),
                        "priority", rule.priority(),
                        "decision", ruleDecision,
                        "matchedConditions", rule.condition()
                ));
                if (matched.size() == 1) {
                    decision = ruleDecision;
                }
            }
        }

        List<UUID> exceptionIds = repository.activeExceptionIds(request.policyName(), request.policyVersion(),
                request.targetType(), request.targetId());
        if (!exceptionIds.isEmpty() && isBlocking(decision)) {
            decision = "ALLOW_WITH_LIMITS";
        }

        List<String> nextSteps = nextSteps(decision, exceptionIds);
        Map<String, Object> explanation = Map.of(
                "inputSnapshot", input,
                "matchedRules", matched,
                "appliedExceptions", exceptionIds,
                "recommendedNextSteps", nextSteps,
                "deterministicRulesVersion", "deterministic_rules_v1",
                "explanation", matched.isEmpty()
                        ? "No enabled DSL-lite rules matched this target"
                        : "Decision comes from enabled DSL-lite rules ordered by priority"
        );
        UUID decisionId = repository.insertDecision(request, decision, matched, exceptionIds, explanation);
        outboxRepository.insert("POLICY_DECISION", decisionId, null, "POLICY_DECISION_RECORDED",
                Map.of("policyName", request.policyName(), "policyVersion", request.policyVersion(), "decision", decision));
        return repository.decision(decisionId).orElseThrow(() -> new NotFoundException("Policy decision not found"));
    }

    public PolicyDecisionResponse decision(UUID id) {
        return repository.decision(id).orElseThrow(() -> new NotFoundException("Policy decision not found"));
    }

    public List<PolicyDecisionResponse> decisions(String targetType, UUID targetId) {
        return repository.decisions(targetType, targetId);
    }

    public PolicyDecisionResponse explain(String targetType, UUID targetId, String policyName, String policyVersion) {
        return repository.decisions(targetType, targetId).stream()
                .filter(decision -> policyName == null || decision.policyName().equals(policyName))
                .filter(decision -> policyVersion == null || decision.policyVersion().equals(policyVersion))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Policy decision not found"));
    }

    private boolean isBlocking(String decision) {
        return List.of("REQUIRE_EXTRA_EVIDENCE", "REQUIRE_VERIFICATION", "REQUIRE_MANUAL_REVIEW",
                "HIDE_LISTING", "BLOCK_TRANSACTION", "RESTRICT_CAPABILITY", "SUSPEND_ACCOUNT",
                "SUPPRESS_REVIEW_WEIGHT").contains(decision);
    }

    private List<String> nextSteps(String decision, List<UUID> exceptionIds) {
        if (!exceptionIds.isEmpty()) {
            return List.of("Scoped approved exception applied", "Review exception expiry before repeating this action");
        }
        return switch (decision) {
            case "ALLOW" -> List.of("Proceed under current deterministic policy");
            case "ALLOW_WITH_LIMITS" -> List.of("Proceed with limits and retain audit evidence");
            case "REQUIRE_EXTRA_EVIDENCE" -> List.of("Create or satisfy evidence requirement");
            case "REQUIRE_VERIFICATION" -> List.of("Request participant verification");
            case "REQUIRE_MANUAL_REVIEW" -> List.of("Queue for operator review");
            case "HIDE_LISTING" -> List.of("Hide listing until policy issue is resolved");
            case "BLOCK_TRANSACTION" -> List.of("Reject transaction mutation");
            case "RESTRICT_CAPABILITY" -> List.of("Restrict only the affected capability");
            case "SUSPEND_ACCOUNT" -> List.of("Require senior review before account suspension");
            case "SUPPRESS_REVIEW_WEIGHT" -> List.of("Reduce or zero one review weight with audit evidence");
            default -> List.of("Record deterministic policy decision");
        };
    }
}
