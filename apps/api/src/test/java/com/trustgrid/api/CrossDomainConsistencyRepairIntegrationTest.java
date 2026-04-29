package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CrossDomainConsistencyRepairIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void crossDomainConsistencyCreatesDedupedFindingsAndRepairRecommendations() {
        UUID participant = createCapableParticipant("cross-" + suffix(), "Cross", "BUY");
        UUID caseId = openTrustCase(participant);
        jdbcTemplate.update("update trust_cases set status = 'ASSIGNED', assigned_to = null where id = ?", caseId);
        post("/api/v1/consistency/checks/full", actorRisk(), null);
        post("/api/v1/consistency/checks/full", actorRisk(), null);
        assertThat(countRows("select count(*) from consistency_findings where finding_type = 'TRUST_CASE_ASSIGNEE_MISSING' and status = 'OPEN'")).isEqualTo(1);
        post("/api/v1/data-repair/recommendations/generate", Map.of(), null);
        assertThat(getList("/api/v1/data-repair/recommendations").getBody().toString()).contains("REQUEST_TRUST_CASE_TARGET_REVIEW");
    }
}
