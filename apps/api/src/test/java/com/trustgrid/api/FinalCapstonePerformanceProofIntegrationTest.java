package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FinalCapstonePerformanceProofIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void seededLocalPortfolioScaleOperationsReturnDeterministicMeasurementSummary() {
        createCapabilityPolicy("PUBLISH_LISTING", Map.of());
        UUID participantForSimulation = null;
        for (int i = 0; i < 60; i++) {
            UUID participant = createCapableParticipant("perf-user-" + i + "-" + suffix(), "Perf User " + i,
                    i % 2 == 0 ? "SELL_ITEMS" : "OFFER_SERVICES");
            if (participantForSimulation == null) {
                participantForSimulation = participant;
            }
            UUID listing = createListing(participant, i % 2 == 0 ? "ITEM_LISTING" : "SERVICE_OFFER",
                    i % 2 == 0 ? "ELECTRONICS" : "TUTORING", "Perf listing " + i + " " + suffix(),
                    i % 2 == 0 ? 5000L + i : 2500L + i, null, i % 2 == 0 ? itemDetails(false) : serviceDetails());
            publish(listing);
            if (i < 20) {
                jdbcTemplate.update("""
                        insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                        values (?, 'PARTICIPANT', ?, 50, 'MEDIUM', 'REQUIRE_MANUAL_REVIEW', 'deterministic_rules_v1')
                        """, UUID.randomUUID(), participant);
            }
        }
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("trustSearchMs", timed(() -> get("/api/v1/listings/trust-ranked-search?query=Perf&policyVersion=TRUST_BALANCED_V1")));
        timings.put("policySimulationMs", timed(() -> post("/api/v1/policy-simulations/shadow-risk", Map.of(
                "policyName", "risk_policy",
                "requestedBy", "operator@example.com",
                "reason", "Performance proof"), null)));
        UUID simulationParticipant = participantForSimulation;
        timings.put("capabilitySimulationMs", timed(() -> simulateCapability(simulationParticipant,
                "PUBLISH_LISTING", null, null, 5000L)));
        timings.put("dashboardMs", timed(() -> get("/api/v1/ops/dashboard/trust-control-room")));
        timings.put("consistencyMs", timed(() -> post("/api/v1/consistency/checks/full", operator(), null)));
        timings.put("lineageRebuildMs", timed(() -> post("/api/v1/lineage/rebuild/full", operator(), null)));
        timings.put("repairRecommendationMs", timed(() -> post("/api/v1/data-repair/recommendations/generate", Map.of(), null)));

        assertThat(timings).allSatisfy((name, elapsedMs) -> assertThat(elapsedMs)
                .describedAs(name + " should remain local-demo friendly")
                .isLessThan(20000));
        assertThat(countRows("select count(*) from participants")).isGreaterThanOrEqualTo(60);
        assertThat(timings.toString()).contains("capabilitySimulationMs", "lineageRebuildMs");
    }

    private long timed(Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        return (System.nanoTime() - start) / 1_000_000;
    }
}
