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
        assertThat(post("/api/v1/trust-scale/seed", Map.of("requestedBy", "operator@example.com", "reason", "Scale proof"), null).getBody().get("status")).isEqualTo("SUCCEEDED");
    }
}
