package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilityGovernanceConsistencyRepairIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void capabilityGovernanceFindingsDedupeAndRepairExecutesBeforeResolving() {
        UUID participant = createCapableParticipant("cap-repair-" + suffix(), "Cap Repair", "OFFER_SERVICES");
        UUID grant = temporaryGrant(participant, "ACCEPT_TRANSACTION", null, null, future());
        jdbcTemplate.update("update temporary_capability_grants set expires_at = now() - interval '5 minutes' where id = ?", grant);

        post("/api/v1/consistency/checks/capability", operator(), null);
        post("/api/v1/consistency/checks/capability", operator(), null);
        assertThat(countRows("""
                select count(*) from consistency_findings
                where finding_type = 'EXPIRED_TEMPORARY_GRANT_STILL_ACTIVE'
                  and target_type = 'TEMPORARY_CAPABILITY_GRANT'
                  and target_id = ? and status = 'OPEN'
                """, grant)).isEqualTo(1);

        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        UUID recommendation = (UUID) jdbcTemplate.queryForMap("""
                select id from data_repair_recommendations
                where repair_type = 'EXPIRE_TEMPORARY_CAPABILITY_GRANT' and target_id = ?
                order by created_at desc limit 1
                """, grant).get("id");

        assertThat(post("/api/v1/data-repair/recommendations/" + recommendation + "/apply",
                Map.of("actor", "operator@example.com", "reason", "missing risk acknowledgement"),
                null).getStatusCode().value()).isEqualTo(400);

        post("/api/v1/data-repair/recommendations/" + recommendation + "/apply", actorRisk(), null);
        assertThat(countRows("select count(*) from temporary_capability_grants where id = ? and status = 'EXPIRED'", grant))
                .isEqualTo(1);
        assertThat(countRows("""
                select count(*) from consistency_findings
                where target_id = ? and status = 'RESOLVED'
                """, grant)).isEqualTo(1);

        UUID override = breakGlass(participant, "ACCEPT_TRANSACTION", null, null, future());
        jdbcTemplate.update("update break_glass_capability_actions set expires_at = now() - interval '5 minutes' where id = ?", override);
        post("/api/v1/consistency/checks/capability", operator(), null);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        UUID breakGlassRecommendation = (UUID) jdbcTemplate.queryForMap("""
                select id from data_repair_recommendations
                where repair_type = 'EXPIRE_BREAK_GLASS_CAPABILITY_ACTION' and target_id = ?
                order by created_at desc limit 1
                """, override).get("id");
        post("/api/v1/data-repair/recommendations/" + breakGlassRecommendation + "/apply", actorRisk(), null);
        assertThat(countRows("select count(*) from break_glass_capability_actions where id = ? and status = 'EXPIRED'", override))
                .isEqualTo(1);
    }
}
