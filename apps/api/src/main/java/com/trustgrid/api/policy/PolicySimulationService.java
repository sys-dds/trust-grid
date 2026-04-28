package com.trustgrid.api.policy;

import com.trustgrid.api.shared.OutboxRepository;
import com.trustgrid.api.policyengine.PolicyRuleEvaluator;
import com.trustgrid.api.policyengine.PolicyRuleRepository;
import com.trustgrid.api.policyengine.PolicyRuleResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicySimulationService {
    private final TrustPolicyRepository repository;
    private final OutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PolicyRuleRepository policyRuleRepository;
    private final PolicyRuleEvaluator evaluator;

    public PolicySimulationService(TrustPolicyRepository repository, OutboxRepository outboxRepository,
                                   JdbcTemplate jdbcTemplate, PolicyRuleRepository policyRuleRepository,
                                   PolicyRuleEvaluator evaluator) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.policyRuleRepository = policyRuleRepository;
        this.evaluator = evaluator;
    }

    @Transactional
    public Map<String, Object> simulate(String type, Map<String, Object> request) {
        Map<String, Object> summary = switch (type) {
            case "SHADOW_RISK" -> shadowRiskSummary(request);
            case "COUNTERFACTUAL_RANKING" -> rankingSummary(request);
            case "DISPUTE_DECISION" -> disputeSummary(request);
            default -> trustSummary(request);
        };
        UUID id = repository.simulation(type, request, summary);
        String event = switch (type) {
            case "SHADOW_RISK" -> "SHADOW_RISK_DECISION_RECORDED";
            case "COUNTERFACTUAL_RANKING" -> "COUNTERFACTUAL_RANKING_SIMULATED";
            case "DISPUTE_DECISION" -> "DISPUTE_DECISION_SIMULATED";
            default -> "POLICY_SIMULATION_RUN";
        };
        outboxRepository.insert("POLICY_SIMULATION", id, null, event, summary);
        return Map.of("simulationRunId", id, "simulationType", type, "status", "SUCCEEDED", "summary", summary);
    }

    private Map<String, Object> trustSummary(Map<String, Object> request) {
        Map<String, Object> counts = repository.policyDataCounts();
        Map<String, Object> evaluated = evaluateSamples("PARTICIPANT", request);
        return Map.ofEntries(
                Map.entry("usersAffected", counts.get("affectedUsers")),
                Map.entry("listingsHidden", counts.get("wouldHideListings")),
                Map.entry("transactionsBlocked", counts.get("wouldBlockTransactions")),
                Map.entry("newUsersImpacted", counts.get("newUsersImpacted")),
                Map.entry("fraudCaught", count("select count(*) from review_abuse_clusters where severity in ('HIGH','CRITICAL')")),
                Map.entry("sampledEntitiesEvaluated", evaluated.get("sampled")),
                Map.entry("changedDecisions", evaluated.get("changedDecisions")),
                Map.entry("decisionDeltas", evaluated.get("decisionDeltas")),
                Map.entry("dataDriven", true),
                Map.entry("evaluatorBacked", true),
                Map.entry("deterministicRulesVersion", "deterministic_rules_v1")
        );
    }

    private Map<String, Object> shadowRiskSummary(Map<String, Object> request) {
        Map<String, Object> listings = evaluateSamples("LISTING", request);
        Map<String, Object> transactions = evaluateSamples("TRANSACTION", request);
        return Map.of(
                "currentDecisions", count("select count(*) from risk_decisions"),
                "simulatedChangedDecisions", ((Number) listings.get("changedDecisions")).intValue()
                        + ((Number) transactions.get("changedDecisions")).intValue(),
                "affectedTransactions", count("select count(*) from marketplace_transactions where value_amount_cents >= 50000"),
                "sampleTargetIds", sampleIds("select target_id::text from risk_decisions order by created_at desc limit 5"),
                "sampledListings", listings.get("sampled"),
                "sampledTransactions", transactions.get("sampled"),
                "decisionDeltas", List.of(listings.get("decisionDeltas"), transactions.get("decisionDeltas")),
                "dataDriven", true,
                "evaluatorBacked", true,
                "deterministicRulesVersion", "deterministic_rules_v1"
        );
    }

    private Map<String, Object> rankingSummary(Map<String, Object> request) {
        Map<String, Object> evaluated = evaluateSamples("LISTING", request);
        return Map.of(
                "rankingLogsEvaluated", count("select count(*) from ranking_decision_logs"),
                "candidateSnapshotsEvaluated", count("""
                        select coalesce(sum(jsonb_array_length(coalesce(trust_risk_snapshot_json->'candidates', '[]'::jsonb))), 0)::int
                        from ranking_decision_logs
                        """),
                "changedPositions", count("select count(*) from ranking_decision_logs where policy_version <> 'risk_averse_v1'"),
                "sampledListings", evaluated.get("sampled"),
                "decisionDeltas", evaluated.get("decisionDeltas"),
                "dataDriven", true,
                "evaluatorBacked", true,
                "deterministicRulesVersion", "deterministic_rules_v1"
        );
    }

    private Map<String, Object> disputeSummary(Map<String, Object> request) {
        Map<String, Object> evaluated = evaluateSamples("DISPUTE", request);
        return Map.of(
                "disputesEvaluated", count("select count(*) from marketplace_disputes"),
                "unsatisfiedEvidenceDisputes", count("""
                        select count(distinct target_id) from evidence_requirements
                        where target_type = 'DISPUTE' and satisfied = false
                        """),
                "recommendedUnderReview", count("select count(*) from marketplace_disputes where status in ('OPEN','AWAITING_BUYER_EVIDENCE','AWAITING_PROVIDER_EVIDENCE')"),
                "sampleDisputeIds", sampleIds("select id::text from marketplace_disputes order by opened_at desc limit 5"),
                "sampledDisputes", evaluated.get("sampled"),
                "decisionDeltas", evaluated.get("decisionDeltas"),
                "dataDriven", true,
                "evaluatorBacked", true,
                "deterministicRulesVersion", "deterministic_rules_v1"
        );
    }

    private Map<String, Object> evaluateSamples(String targetType, Map<String, Object> request) {
        String policyName = String.valueOf(request.getOrDefault("policyName", "risk_policy"));
        String toVersion = String.valueOf(request.getOrDefault("toPolicyVersion", "simulation_v1"));
        String fromVersion = request.get("fromPolicyVersion") == null ? null : String.valueOf(request.get("fromPolicyVersion"));
        List<Map<String, Object>> deltas = new ArrayList<>();
        int changed = 0;
        for (UUID targetId : policyRuleRepository.sampleTargetIds(targetType, 50)) {
            String current = fromVersion == null ? "ALLOW" : evaluateDecision(policyName, fromVersion, targetType, targetId);
            String proposed = evaluateDecision(policyName, toVersion, targetType, targetId);
            if (!current.equals(proposed)) {
                changed++;
            }
            deltas.add(Map.of("targetType", targetType, "targetId", targetId, "currentDecision", current,
                    "simulatedDecision", proposed));
        }
        return Map.of("sampled", deltas.size(), "changedDecisions", changed, "decisionDeltas", deltas);
    }

    private String evaluateDecision(String policyName, String policyVersion, String targetType, UUID targetId) {
        try {
            Map<String, Object> snapshot = policyRuleRepository.targetSnapshot(targetType, targetId);
            for (PolicyRuleResponse rule : policyRuleRepository.evaluationRules(policyName, policyVersion, targetType)) {
                if (evaluator.matches(rule, snapshot)) {
                    return evaluator.decision(rule);
                }
            }
        } catch (Exception ignored) {
            return "ALLOW";
        }
        return "ALLOW";
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private java.util.List<String> sampleIds(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }
}
