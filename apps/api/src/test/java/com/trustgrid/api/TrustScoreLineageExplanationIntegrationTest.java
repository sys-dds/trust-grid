package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustScoreLineageExplanationIntegrationTest extends Tg181To220IntegrationTestSupport {

    @Test
    void trustScoreLineageAndExplanationAreRebuiltExplicitlyAndReadsAreReadOnly() {
        Flow flow = createCompletedServiceFlow("lineage");
        review(flow.transactionId(), flow.buyerId(), flow.providerId(), 5, "Helpful reliable service", "lineage-review-" + suffix());
        int beforeRead = countRows("select count(*) from trust_score_lineage_entries");
        assertThat(get("/api/v1/participants/" + flow.providerId() + "/trust-score/explanation").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(countRows("select count(*) from trust_score_lineage_entries")).isEqualTo(beforeRead);

        var rebuild = post("/api/v1/lineage/rebuild/trust-score", operator(), null);
        assertThat(rebuild.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getList("/api/v1/participants/" + flow.providerId() + "/trust-score/lineage").getBody())
                .isNotEmpty();
        assertThat(get("/api/v1/participants/" + flow.providerId() + "/trust-score/explanation").getBody().toString())
                .contains("Review contribution", "deterministic");
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'TRUST_SCORE_LINEAGE_RECORDED'"))
                .isGreaterThanOrEqualTo(1);
    }
}
