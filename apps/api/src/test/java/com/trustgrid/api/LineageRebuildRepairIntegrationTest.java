package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LineageRebuildRepairIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void lineageRebuildAndOperatorRepairRecommendationsAreAuditedAndExplicit() {
        UUID participant = createCapableParticipant("repair-" + suffix(), "Repair User", "BUY");
        jdbcTemplate.update("update participants set account_status = 'SUSPENDED' where id = ?", participant);
        jdbcTemplate.update("update trust_profiles set trust_tier = 'HIGH_TRUST', trust_score = 900 where participant_id = ?", participant);

        post("/api/v1/consistency/checks/full", operator(), null);
        var generated = post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        assertThat(generated.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(generated.getBody().get("autoRepair")).isEqualTo(false);

        UUID recommendation = firstIdFromList("/api/v1/data-repair/recommendations", "id");
        post("/api/v1/data-repair/recommendations/" + recommendation + "/approve", actorRisk(), null);
        var applied = post("/api/v1/data-repair/recommendations/" + recommendation + "/apply", actorRisk(), null);
        assertThat(applied.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(applied.getBody().get("status")).isEqualTo("APPLIED");

        var rebuild = post("/api/v1/lineage/rebuild/full", operator(), null);
        assertThat(rebuild.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/lineage/rebuild-runs").getBody()).isNotEmpty();
        assertThat(getList("/api/v1/data-repair/actions").getBody()).isNotEmpty();
        assertThat(countRows("select count(*) from marketplace_events where event_type in ('DATA_REPAIR_RECOMMENDED','OPERATOR_DATA_REPAIR_APPLIED','LINEAGE_REBUILD_RUN')"))
                .isGreaterThanOrEqualTo(3);
    }
}
