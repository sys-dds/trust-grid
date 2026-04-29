package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignGraphContainmentIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void campaignGraphBlastRadiusAndSimulationWorkWithoutMutation() {
        UUID campaign = createCampaign();
        assertThat(post("/api/v1/trust-campaigns/" + campaign + "/graph/rebuild", Map.of(), null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/trust-campaigns/" + campaign + "/graph").getBody()).isNotEmpty();
        assertThat(get("/api/v1/trust-campaigns/" + campaign + "/blast-radius").getBody().get("deterministic")).isEqualTo(true);
        assertThat(post("/api/v1/trust-campaigns/" + campaign + "/containment/simulate", Map.of("actor", "operator@example.com", "reason", "Simulate"), null).getBody().get("wouldMutate")).isEqualTo(false);
    }
}
