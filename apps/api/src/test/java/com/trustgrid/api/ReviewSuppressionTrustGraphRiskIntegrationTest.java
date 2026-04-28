package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewSuppressionTrustGraphRiskIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void suppressionZeroesReviewWeightAndTrustGraphRiskExposesCluster() {
        Flow flow = createCompletedServiceFlow("suppress");
        review(flow.transactionId(), flow.buyerId(), flow.providerId(), 5, "Repeated safe text", "suppress-review-" + suffix());
        jdbcTemplate.update("""
                insert into review_abuse_clusters (id, cluster_type, severity, summary, signals_json, member_participant_ids_json, review_ids_json)
                select gen_random_uuid(), 'SIMILAR_REVIEW_TEXT', 'HIGH', 'manual-test', '["similar_text"]'::jsonb,
                       jsonb_build_array(?::text, ?::text), jsonb_agg(id::text)
                from marketplace_reviews where reviewed_participant_id = ?
                """, flow.buyerId().toString(), flow.providerId().toString(), flow.providerId());
        UUID cluster = firstCluster();
        post("/api/v1/review-graph/clusters/" + cluster + "/suppress-review-weight",
                Map.of("actor", "moderator@example.com", "reason", "Suspicious review cluster"), null);
        assertThat(countRows("select count(*) from marketplace_reviews where status = 'SUPPRESSED' and reviewed_participant_id = ?", flow.providerId())).isPositive();
        Map<?, ?> risk = get("/api/v1/participants/" + flow.providerId() + "/trust-graph-risk").getBody();
        assertThat(risk.get("clusterCount")).isNotEqualTo(0);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'REVIEW_WEIGHT_SUPPRESSED'")).isPositive();
    }
}
