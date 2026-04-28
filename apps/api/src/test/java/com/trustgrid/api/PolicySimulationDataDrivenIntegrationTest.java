package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicySimulationDataDrivenIntegrationTest extends Tg161To180IntegrationTestSupport {

    @Test
    void policySimulationsReadActualMarketplaceDataWithoutMutatingBusinessState() {
        Flow completed = createCompletedServiceFlow("simulation-data");
        Flow disputed = createDisputableServiceFlow("simulation-dispute");
        openDispute(disputed, "simulation-dispute-" + suffix());
        jdbcTemplate.update("""
                insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                values (?, 'TRANSACTION', ?, 80, 'HIGH', 'REQUIRE_MANUAL_REVIEW', 'risk_rules_v1')
                """, UUID.randomUUID(), completed.transactionId());
        get("/api/v1/listings/trust-ranked-search?query=simulation&policyVersion=trust_balanced_v1");

        int listingsBefore = countRows("select count(*) from marketplace_listings");
        Map<?, ?> trust = post("/api/v1/policy-simulations/trust", Map.of(
                "policyName", "risk_policy", "toPolicyVersion", "simulation_v2",
                "requestedBy", "operator@example.com", "reason", "Trust simulation"), null).getBody();
        Map<?, ?> shadow = post("/api/v1/policy-simulations/shadow-risk", Map.of(
                "policyName", "risk_policy", "toPolicyVersion", "simulation_v2",
                "requestedBy", "operator@example.com", "reason", "Shadow risk"), null).getBody();
        Map<?, ?> ranking = post("/api/v1/policy-simulations/counterfactual-ranking", Map.of(
                "policyName", "ranking_policy", "toPolicyVersion", "simulation_rank_v2",
                "requestedBy", "operator@example.com", "reason", "Counterfactual ranking"), null).getBody();
        Map<?, ?> dispute = post("/api/v1/policy-simulations/dispute-decision", Map.of(
                "policyName", "dispute_outcome_policy", "toPolicyVersion", "simulation_dispute_v2",
                "requestedBy", "operator@example.com", "reason", "Dispute simulation"), null).getBody();

        assertThat(trust.toString()).contains("usersAffected", "transactionsBlocked", "dataDriven=true");
        assertThat(shadow.toString()).contains("currentDecisions", "sampleTargetIds", "dataDriven=true");
        assertThat(ranking.toString()).contains("candidateSnapshotsEvaluated", "changedPositions", "dataDriven=true");
        assertThat(dispute.toString()).contains("disputesEvaluated", "unsatisfiedEvidenceDisputes", "dataDriven=true");
        assertThat(countRows("select count(*) from marketplace_listings")).isEqualTo(listingsBefore);
        assertThat(countRows("select count(*) from policy_simulation_runs")).isGreaterThanOrEqualTo(4);
    }
}
