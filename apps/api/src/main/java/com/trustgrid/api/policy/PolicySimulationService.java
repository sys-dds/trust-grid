package com.trustgrid.api.policy;

import com.trustgrid.api.shared.OutboxRepository;
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

    public PolicySimulationService(TrustPolicyRepository repository, OutboxRepository outboxRepository,
                                   JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> simulate(String type, Map<String, Object> request) {
        Map<String, Object> summary = switch (type) {
            case "SHADOW_RISK" -> shadowRiskSummary();
            case "COUNTERFACTUAL_RANKING" -> rankingSummary();
            case "DISPUTE_DECISION" -> disputeSummary();
            default -> trustSummary();
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

    private Map<String, Object> trustSummary() {
        Map<String, Object> counts = repository.policyDataCounts();
        return Map.ofEntries(
                Map.entry("usersAffected", counts.get("affectedUsers")),
                Map.entry("listingsHidden", counts.get("wouldHideListings")),
                Map.entry("transactionsBlocked", counts.get("wouldBlockTransactions")),
                Map.entry("newUsersImpacted", counts.get("newUsersImpacted")),
                Map.entry("fraudCaught", count("select count(*) from review_abuse_clusters where severity in ('HIGH','CRITICAL')")),
                Map.entry("dataDriven", true),
                Map.entry("deterministicRulesVersion", "deterministic_rules_v1")
        );
    }

    private Map<String, Object> shadowRiskSummary() {
        return Map.of(
                "currentDecisions", count("select count(*) from risk_decisions"),
                "simulatedChangedDecisions", count("""
                        select count(*) from risk_decisions
                        where risk_level in ('HIGH','CRITICAL') or decision in ('ALLOW_WITH_LIMITS','REQUIRE_MANUAL_REVIEW')
                        """),
                "affectedTransactions", count("select count(*) from marketplace_transactions where value_amount_cents >= 50000"),
                "sampleTargetIds", sampleIds("select target_id::text from risk_decisions order by created_at desc limit 5"),
                "dataDriven", true,
                "deterministicRulesVersion", "deterministic_rules_v1"
        );
    }

    private Map<String, Object> rankingSummary() {
        return Map.of(
                "rankingLogsEvaluated", count("select count(*) from ranking_decision_logs"),
                "candidateSnapshotsEvaluated", count("""
                        select coalesce(sum(jsonb_array_length(coalesce(trust_risk_snapshot_json->'candidates', '[]'::jsonb))), 0)::int
                        from ranking_decision_logs
                        """),
                "changedPositions", count("select count(*) from ranking_decision_logs where policy_version <> 'risk_averse_v1'"),
                "dataDriven", true,
                "deterministicRulesVersion", "deterministic_rules_v1"
        );
    }

    private Map<String, Object> disputeSummary() {
        return Map.of(
                "disputesEvaluated", count("select count(*) from marketplace_disputes"),
                "unsatisfiedEvidenceDisputes", count("""
                        select count(distinct target_id) from evidence_requirements
                        where target_type = 'DISPUTE' and satisfied = false
                        """),
                "recommendedUnderReview", count("select count(*) from marketplace_disputes where status in ('OPEN','AWAITING_BUYER_EVIDENCE','AWAITING_PROVIDER_EVIDENCE')"),
                "sampleDisputeIds", sampleIds("select id::text from marketplace_disputes order by opened_at desc limit 5"),
                "dataDriven", true,
                "deterministicRulesVersion", "deterministic_rules_v1"
        );
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private java.util.List<String> sampleIds(String sql) {
        return jdbcTemplate.queryForList(sql, String.class);
    }
}
