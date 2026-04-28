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
        evaluator.validateAction(request.action() == null ? Map.of() : request.action());
        evaluator.validateConditions(request.condition() == null ? Map.of() : request.condition());
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

        String originalDecision = decision;
        List<Map<String, Object>> appliedExceptions = compatibleExceptions(request, matched, originalDecision);
        List<Map<String, Object>> ignoredExceptions = ignoredExceptions(request, matched, originalDecision);
        List<UUID> exceptionIds = appliedExceptions.stream()
                .map(exception -> (UUID) exception.get("exceptionId"))
                .toList();
        if (!exceptionIds.isEmpty() && isBlocking(decision)) {
            decision = "ALLOW_WITH_LIMITS";
        }

        List<String> nextSteps = nextSteps(decision, exceptionIds);
        Map<String, Object> explanation = Map.of(
                "inputSnapshot", input,
                "matchedRules", matched,
                "appliedExceptions", appliedExceptions,
                "ignoredExceptions", ignoredExceptions,
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

    private List<Map<String, Object>> compatibleExceptions(EvaluatePolicyRequest request,
                                                           List<Map<String, Object>> matchedRules,
                                                           String decision) {
        if (!isBlocking(decision) || matchedRules.isEmpty()) {
            return List.of();
        }
        Map<String, Object> firstRule = matchedRules.getFirst();
        return repository.activeExceptions(request.policyName(), request.policyVersion(), request.targetType(), request.targetId())
                .stream()
                .filter(exception -> compatible(exception.exceptionType(), decision,
                        String.valueOf(firstRule.get("ruleType")), String.valueOf(firstRule.get("ruleKey")), firstRule))
                .map(exception -> Map.<String, Object>of(
                        "exceptionId", exception.id(),
                        "exceptionType", exception.exceptionType(),
                        "compatibilityReason", "Exception type matches blocked action and rule category"
                ))
                .toList();
    }

    private List<Map<String, Object>> ignoredExceptions(EvaluatePolicyRequest request,
                                                        List<Map<String, Object>> matchedRules,
                                                        String decision) {
        if (!isBlocking(decision) || matchedRules.isEmpty()) {
            return List.of();
        }
        Map<String, Object> firstRule = matchedRules.getFirst();
        return repository.activeExceptions(request.policyName(), request.policyVersion(), request.targetType(), request.targetId())
                .stream()
                .filter(exception -> !compatible(exception.exceptionType(), decision,
                        String.valueOf(firstRule.get("ruleType")), String.valueOf(firstRule.get("ruleKey")), firstRule))
                .map(exception -> Map.<String, Object>of(
                        "exceptionId", exception.id(),
                        "exceptionType", exception.exceptionType(),
                        "ignoredReason", "Exception type is not compatible with this blocked action"
                ))
                .toList();
    }

    private boolean compatible(String exceptionType, String decision, String ruleType, String ruleKey,
                               Map<String, Object> matchedRule) {
        String key = ruleKey.toLowerCase();
        return switch (exceptionType) {
            case "ALLOW_HIGH_VALUE" -> List.of("BLOCK_TRANSACTION", "REQUIRE_MANUAL_REVIEW", "REQUIRE_VERIFICATION").contains(decision)
                    && (key.contains("value") || key.contains("high"));
            case "ALLOW_NEW_USER_ACTION" -> List.of("BLOCK_TRANSACTION", "REQUIRE_MANUAL_REVIEW").contains(decision)
                    && (key.contains("new") || key.contains("limited") || key.contains("ramp"));
            case "BYPASS_EXTRA_EVIDENCE" -> "REQUIRE_EXTRA_EVIDENCE".equals(decision);
            case "TEMPORARY_RANKING_VISIBILITY" -> "RANKING_RULE".equals(ruleType)
                    && ("HIDE_LISTING".equals(decision) || key.contains("visibility") || key.contains("suppression"));
            case "DISPUTE_POLICY_OVERRIDE" -> "DISPUTE_RULE".equals(ruleType)
                    && List.of("REQUIRE_EXTRA_EVIDENCE", "REQUIRE_MANUAL_REVIEW").contains(decision);
            case "REVIEW_WEIGHT_OVERRIDE" -> "REVIEW_WEIGHT_RULE".equals(ruleType)
                    && "SUPPRESS_REVIEW_WEIGHT".equals(decision);
            default -> false;
        };
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
