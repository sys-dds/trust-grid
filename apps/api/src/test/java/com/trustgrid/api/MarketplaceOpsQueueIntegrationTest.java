package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MarketplaceOpsQueueIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void opsQueueRebuildCreatesSearchableDeduplicatedQueueItems() {
        Flow flow = createDisputableServiceFlow("ops-queue");
        UUID dispute = openDispute(flow, "ops-dispute-" + suffix());
        recordEvidence("DISPUTE", dispute, flow.buyerId(), "USER_STATEMENT", "ops-evidence-" + suffix());
        jdbcTemplate.update("update marketplace_listings set risk_tier = 'HIGH', status = 'UNDER_REVIEW' where id = ?", flow.listingId());
        var rebuild = post("/api/v1/ops/queue/rebuild", Map.of(), null);
        assertThat(rebuild.getStatusCode().is2xxSuccessful()).as(String.valueOf(rebuild.getBody())).isTrue();
        var rebuildAgain = post("/api/v1/ops/queue/rebuild", Map.of(), null);
        assertThat(rebuildAgain.getStatusCode().is2xxSuccessful()).as(String.valueOf(rebuildAgain.getBody())).isTrue();
        java.util.List queue = getList("/api/v1/ops/queue").getBody();
        assertThat(queue.toString()).contains("HIGH_RISK_LISTINGS", "OPEN_DISPUTES", "EVIDENCE_MISSING");
        assertThat(countRows("select count(*) from marketplace_ops_queue_items where target_id = ? and queue_type = 'OPEN_DISPUTES'", dispute)).isEqualTo(1);
    }
}
