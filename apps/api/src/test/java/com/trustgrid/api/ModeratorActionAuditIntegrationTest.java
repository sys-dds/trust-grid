package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ModeratorActionAuditIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void moderatorActionsMutateWhereSupportedAndCreateAuditEvents() {
        Flow flow = createCompletedServiceFlow("moderator");
        post("/api/v1/ops/moderator-actions/hide-listing", Map.of(
                "targetType", "LISTING", "targetId", flow.listingId().toString(),
                "actor", "moderator@example.com", "reason", "Risk review"), null);
        post("/api/v1/ops/moderator-actions/request-evidence", Map.of(
                "targetType", "LISTING", "targetId", flow.listingId().toString(),
                "actor", "moderator@example.com", "reason", "Need more evidence"), null);
        assertThat(countRows("select count(*) from moderator_actions")).isGreaterThanOrEqualTo(2);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'MODERATOR_ACTION_RECORDED'")).isGreaterThanOrEqualTo(2);
        assertThat(get("/api/v1/listings/search?query=moderator").getBody().toString()).doesNotContain(flow.listingId().toString());
    }
}
