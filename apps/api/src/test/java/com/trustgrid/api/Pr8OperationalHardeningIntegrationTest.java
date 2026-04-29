package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Pr8OperationalHardeningIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void operationalHardeningRecomputesScopesDeduplicatesAndRepairsBeforeResolving() {
        var zero = post("/api/v1/trust-monitors/run", Map.of(
                "requestedBy", "operator@example.com",
                "reason", "Zero-state monitor proof",
                "windowMinutes", 60
        ), null);
        assertThat(zero.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(countRows("select count(*) from trust_incidents")).isZero();
        assertThat(zero.getBody().toString()).contains("severity=LOW", "incidentId=null");

        UUID participant = createCapableParticipant("pr8-risk-" + suffix(), "PR8 Risk", "BUY");
        for (int i = 0; i < 10; i++) {
            jdbcTemplate.update("""
                    insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                    values (?, 'PARTICIPANT', ?, 90, 'HIGH', 'BLOCK_TRANSACTION', 'deterministic_rules_v1')
                    """, UUID.randomUUID(), participant);
        }
        var high = post("/api/v1/trust-monitors/run", Map.of(
                "requestedBy", "operator@example.com",
                "reason", "High signal monitor proof",
                "windowMinutes", 60
        ), null);
        assertThat(high.getBody().toString()).contains("RISK_SPIKE", "severity=HIGH");
        UUID incident = firstIdFromList("/api/v1/trust-incidents", "id");
        assertThat(countRows("select count(*) from trust_alerts where incident_id = ?", incident)).isEqualTo(1);

        var bundle = get("/api/v1/trust-incidents/" + incident + "/evidence-bundle").getBody();
        assertThat(bundle.toString()).contains("incident_related_records_only", "riskDecisions");
        assertThat(bundle.get("reviewAbuseClusters").toString()).isEqualTo("[]");

        var replay = post("/api/v1/trust-incidents/" + incident + "/replay", Map.of(), null).getBody();
        assertThat(replay.get("matchedOriginal")).isEqualTo(true);
        jdbcTemplate.update("update risk_decisions set decision = 'ALLOW' where id = (select id from risk_decisions limit 1)");
        var mismatched = post("/api/v1/trust-incidents/" + incident + "/replay", Map.of(), null).getBody();
        assertThat(mismatched.get("matchedOriginal")).isEqualTo(false);
        assertThat(mismatched.get("mismatchReasons").toString()).contains("transactions_blocked");

        Flow flow = createCompletedServiceFlow("pr8-lineage");
        review(flow.transactionId(), flow.buyerId(), flow.providerId(), 5, "Lineage proof", "pr8-review-" + suffix());
        jdbcTemplate.update("""
                insert into trust_score_lineage_entries (
                  id, participant_id, source_type, source_id, contribution_type, contribution_value, policy_version, explanation
                ) values (?, ?, 'MODERATOR_ACTION', ?, 'NEUTRAL', 1, 'manual_policy', 'Manual lineage entry')
                """, UUID.randomUUID(), flow.providerId(), UUID.randomUUID());
        post("/api/v1/lineage/rebuild/full", operator(), null);
        int lineageCount = countRows("select count(*) from trust_score_lineage_entries");
        post("/api/v1/lineage/rebuild/full", operator(), null);
        assertThat(countRows("select count(*) from trust_score_lineage_entries")).isEqualTo(lineageCount);
        assertThat(countRows("select count(*) from trust_score_lineage_entries where policy_version = 'manual_policy'")).isEqualTo(1);

        updateStatus(flow.providerId(), "SUSPENDED");
        jdbcTemplate.update("update trust_profiles set trust_tier = 'HIGH_TRUST', trust_score = 900 where participant_id = ?", flow.providerId());
        post("/api/v1/consistency/checks/full", operator(), null);
        int findingCount = countRows("select count(*) from consistency_findings where status = 'OPEN'");
        post("/api/v1/consistency/checks/full", operator(), null);
        assertThat(countRows("select count(*) from consistency_findings where status = 'OPEN'")).isEqualTo(findingCount);

        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        UUID recommendation = firstIdFromList("/api/v1/data-repair/recommendations", "id");
        var apply = post("/api/v1/data-repair/recommendations/" + recommendation + "/apply", actorRisk(), null);
        assertThat(apply.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/data-repair/actions").getBody().toString())
                .contains("repairExecuted");
    }
}
