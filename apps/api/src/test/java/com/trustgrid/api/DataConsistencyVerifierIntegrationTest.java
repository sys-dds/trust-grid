package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataConsistencyVerifierIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void consistencyVerifiersCreateFindingsWithoutAutoRepair() {
        UUID participant = createCapableParticipant("consistency-" + suffix(), "Consistency User", "BUY");
        jdbcTemplate.update("update participants set account_status = 'SUSPENDED' where id = ?", participant);
        jdbcTemplate.update("update trust_profiles set trust_tier = 'HIGH_TRUST', trust_score = 900 where participant_id = ?", participant);
        jdbcTemplate.update("update participant_capabilities set status = 'ACTIVE' where participant_id = ?", participant);

        String[] endpoints = {
                "/api/v1/consistency/checks/trust-profile",
                "/api/v1/consistency/checks/reputation-rebuild",
                "/api/v1/consistency/checks/search-index",
                "/api/v1/consistency/checks/event-analytics",
                "/api/v1/consistency/checks/evidence-reference",
                "/api/v1/consistency/checks/dispute",
                "/api/v1/consistency/checks/capability",
                "/api/v1/consistency/checks/full"
        };
        for (String endpoint : endpoints) {
            var response = post(endpoint, operator(), null);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody().get("autoRepair")).isEqualTo(false);
        }
        assertThat(getList("/api/v1/consistency/findings").getBody()).isNotEmpty();
        assertThat(getList("/api/v1/consistency/check-runs").getBody()).hasSizeGreaterThanOrEqualTo(8);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'CONSISTENCY_CHECK_RUN'"))
                .isGreaterThanOrEqualTo(8);
    }
}
