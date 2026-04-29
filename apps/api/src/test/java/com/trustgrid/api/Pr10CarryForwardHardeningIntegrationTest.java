package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Pr10CarryForwardHardeningIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void repairAuditBundleAndReplaySnapshotAreHardened() {
        UUID participant = createCapableParticipant("pr10-" + suffix(), "PR10", "BUY");
        UUID grant = temporaryGrant(participant, "ACCEPT_TRANSACTION", null, null, future());
        jdbcTemplate.update("update temporary_capability_grants set expires_at = now() - interval '1 minute' where id = ?", grant);
        post("/api/v1/consistency/checks/capability", actorRisk(), null);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        Map<?, ?> recommendation = (Map<?, ?>) getList("/api/v1/data-repair/recommendations").getBody().getFirst();
        UUID rec = UUID.fromString(recommendation.get("id").toString());
        assertThat(post("/api/v1/data-repair/recommendations/" + rec + "/apply", Map.of("actor", "operator@example.com", "reason", "missing ack"), null)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(post("/api/v1/data-repair/recommendations/" + rec + "/apply", actorRisk(), null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(jdbcTemplate.queryForObject("select status from temporary_capability_grants where id = ?", String.class, grant)).isEqualTo("EXPIRED");
        createCapabilityPolicy("ACCEPT_TRANSACTION", Map.of("maxValueCents", 100));
        Map<?, ?> decision = simulateCapability(participant, "ACCEPT_TRANSACTION", null, null, 500L);
        UUID decisionId = UUID.fromString(decision.get("decisionId").toString());
        assertThat(jdbcTemplate.queryForObject("select policy_hash is not null from capability_decision_logs where id = ?", Boolean.class, decisionId)).isTrue();
        assertThat(get("/api/v1/capability-governance/audit-bundle/participants/" + participant).getBody().toString())
                .contains(grant.toString(), "EXPIRED_TEMPORARY_GRANT_STILL_ACTIVE");
    }
}
