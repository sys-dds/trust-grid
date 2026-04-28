package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReplayRebuildConsistencyIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void rebuildsAndReplayAreDuplicateSafeAndCreateFindings() {
        createCompletedServiceFlow("rebuild");
        post("/api/v1/rebuilds/reputation", actorReason(), null);
        post("/api/v1/rebuilds/search-index", actorReason(), null);
        jdbcTemplate.update("""
                insert into marketplace_evidence (id, target_type, target_id, evidence_type, object_key)
                values (?, 'TRANSACTION', ?, 'RECEIPT', 'placeholder/bad')
                """, UUID.randomUUID(), UUID.randomUUID());
        post("/api/v1/consistency/evidence/verify", actorReason(), null);
        post("/api/v1/replay/outbox", actorReason(), null);
        post("/api/v1/replay/outbox", actorReason(), null);
        post("/api/v1/replay/audit-timeline", actorReason(), null);
        assertThat(countRows("select count(*) from rebuild_runs")).isGreaterThanOrEqualTo(5);
        assertThat(countRows("select count(*) from consistency_findings where finding_type = 'EVIDENCE_REFERENCE_INVALID'")).isPositive();
        assertThat(getList("/api/v1/consistency/findings").getBody().toString()).contains("EVIDENCE_REFERENCE_INVALID");
    }
}
