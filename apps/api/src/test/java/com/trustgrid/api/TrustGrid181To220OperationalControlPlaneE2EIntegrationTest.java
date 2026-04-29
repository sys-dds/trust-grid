package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustGrid181To220OperationalControlPlaneE2EIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void operationalTrustControlPlaneWorksEndToEnd() {
        Flow flow = createCompletedServiceFlow("ops-e2e");
        review(flow.transactionId(), flow.buyerId(), flow.providerId(), 5, "Operationally sound service", "ops-review-" + suffix());
        get("/api/v1/listings/trust-ranked-search?query=service&policyVersion=trust_balanced_v1");

        String version = "ops_policy_" + suffix();
        createRiskPolicyWithRule(version, "high_value_block_e2e", "RISK_RULE", "LISTING",
                condition("valueCents", "greater_than", 1), "BLOCK_TRANSACTION");
        requestAndApproveException("risk_policy", version, "LISTING", flow.listingId(), "BYPASS_EXTRA_EVIDENCE");
        assertThat(evaluatePolicy("risk_policy", version, "LISTING", flow.listingId(), Map.of()).get("decision"))
                .isEqualTo("BLOCK_TRANSACTION");
        requestAndApproveException("risk_policy", version, "LISTING", flow.listingId(), "ALLOW_HIGH_VALUE");
        assertThat(evaluatePolicy("risk_policy", version, "LISTING", flow.listingId(), Map.of()).get("decision"))
                .isEqualTo("ALLOW_WITH_LIMITS");

        assertThat(post("/api/v1/policy-simulations/shadow-risk", Map.of(
                "policyName", "risk_policy",
                "fromPolicyVersion", version,
                "toPolicyVersion", version,
                "requestedBy", "operator@example.com",
                "reason", "Operational simulation"), null).getBody().toString())
                .contains("evaluatorBacked");

        post("/api/v1/trust-slos", Map.of(
                "sloKey", "ops-e2e-slo",
                "name", "Ops E2E SLO",
                "targetType", "MODERATION_BACKLOG",
                "thresholdValue", 0,
                "windowMinutes", 60,
                "severity", "HIGH"
        ), null);
        for (int i = 0; i < 10; i++) {
            jdbcTemplate.update("""
                    insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                    values (?, 'PARTICIPANT', ?, 90, 'HIGH', 'BLOCK_TRANSACTION', 'deterministic_rules_v1')
                    """, UUID.randomUUID(), flow.providerId());
        }
        post("/api/v1/trust-monitors/run", Map.of("requestedBy", "operator@example.com",
                "reason", "Operational E2E monitor", "windowMinutes", 60), null);
        UUID incident = firstIdFromList("/api/v1/trust-incidents", "id");
        post("/api/v1/trust-incidents/" + incident + "/status", Map.of("status", "INVESTIGATING",
                "actor", "operator@example.com", "reason", "Investigate"), null);
        post("/api/v1/trust-incidents/" + incident + "/status", Map.of("status", "MITIGATED",
                "actor", "operator@example.com", "reason", "Mitigate"), null);
        post("/api/v1/trust-incidents/" + incident + "/status", Map.of("status", "RESOLVED",
                "actor", "operator@example.com", "reason", "Resolve"), null);
        assertThat(get("/api/v1/trust-incidents/" + incident + "/evidence-bundle").getBody().toString()).contains("timeline");
        UUID alert = firstIdFromList("/api/v1/trust-alerts", "id");
        post("/api/v1/trust-alerts/" + alert + "/acknowledge", Map.of("actor", "operator@example.com", "reason", "Ack"), null);
        assertThat(get("/api/v1/ops/dashboard/trust-control-room").getBody().toString()).contains("openIncidents");
        assertThat(post("/api/v1/trust-incidents/" + incident + "/replay", Map.of(), null).getBody().get("matchedOriginal")).isEqualTo(true);

        post("/api/v1/lineage/rebuild/full", operator(), null);
        assertThat(get("/api/v1/participants/" + flow.providerId() + "/trust-score/explanation").getBody().toString()).contains("lineageEntries");
        jdbcTemplate.update("update participants set account_status = 'SUSPENDED' where id = ?", flow.providerId());
        jdbcTemplate.update("update trust_profiles set trust_tier = 'HIGH_TRUST', trust_score = 900 where participant_id = ?", flow.providerId());
        post("/api/v1/consistency/checks/full", operator(), null);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        UUID recommendation = firstIdFromList("/api/v1/data-repair/recommendations", "id");
        post("/api/v1/data-repair/recommendations/" + recommendation + "/apply", actorRisk(), null);

        assertThat(get("/actuator/health").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getStatusCode().is2xxSuccessful()).isTrue();
    }
}
