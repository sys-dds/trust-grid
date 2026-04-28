package com.trustgrid.api.policy;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicySimulationService {
    private final TrustPolicyRepository repository;
    private final OutboxRepository outboxRepository;

    public PolicySimulationService(TrustPolicyRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> simulate(String type, Map<String, Object> request) {
        Map<String, Object> summary = switch (type) {
            case "SHADOW_RISK" -> Map.of("currentDecision", "ALLOW", "simulatedDecision", "REQUIRE_MANUAL_REVIEW", "affectedTransactions", 1);
            case "COUNTERFACTUAL_RANKING" -> Map.of("currentOrder", java.util.List.of(), "simulatedOrder", java.util.List.of(), "delta", 0);
            case "DISPUTE_DECISION" -> Map.of("currentOutcome", "INSUFFICIENT_EVIDENCE", "simulatedOutcome", "UNDER_REVIEW");
            default -> Map.of("usersAffected", 0, "listingsHidden", 0, "transactionsBlocked", 0, "newUsersImpacted", 0);
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
}
