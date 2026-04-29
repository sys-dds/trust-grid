package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustDossierAndScaleProofIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void dossiersControlRoomGraphSummaryAndScaleSeedWork() {
        UUID participant = createCapableParticipant("dossier-" + suffix(), "Dossier", "BUY");
        UUID campaign = createCampaign();
        assertThat(get("/api/v1/trust-dossiers/participants/" + participant).getBody().get("scope")).isEqualTo("target_only");
        assertThat(get("/api/v1/trust-dossiers/campaigns/" + campaign).getBody().get("dossierType")).isEqualTo("CAMPAIGN");
        assertThat(post("/api/v1/trust-dossiers/snapshots", Map.of("dossierType", "PARTICIPANT", "targetId", participant.toString(), "generatedBy", "operator@example.com", "reason", "Snapshot"), null).getBody().get("snapshotId")).isNotNull();
        assertThat(get("/api/v1/trust-control-room/aggregate").getBody().get("openCampaigns")).isNotNull();
        assertThat(get("/api/v1/trust-control-room/marketplace-graph-summary").getBody().get("participants")).isNotNull();
        int participantsBefore = countRows("select count(*) from participants");
        int casesBefore = countRows("select count(*) from trust_cases");
        Map<?, ?> seed = post("/api/v1/trust-scale/seed", Map.of(
                "participants", 3,
                "trustCases", 2,
                "campaigns", 1,
                "attackRuns", 1,
                "requestedBy", "operator@example.com",
                "reason", "Scale proof"
        ), null).getBody();
        assertThat(seed.get("status")).isEqualTo("SUCCEEDED");
        Map<?, ?> createdCounts = (Map<?, ?>) seed.get("createdCounts");
        assertThat(createdCounts.get("participants")).isEqualTo(3);
        assertThat(countRows("select count(*) from participants")).isGreaterThanOrEqualTo(participantsBefore + 3);
        assertThat(countRows("select count(*) from trust_cases")).isGreaterThan(casesBefore);
        assertThat(get("/api/v1/trust-control-room/aggregate").getBody().get("openCases")).isNotNull();
        assertThat(post("/api/v1/trust-scale/seed", Map.of("participants", 999, "requestedBy", "operator@example.com", "reason", "Bound proof"), null).getBody().toString()).contains("250");
    }
}
