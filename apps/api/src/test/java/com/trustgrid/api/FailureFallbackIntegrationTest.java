package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FailureFallbackIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void failureMatrixRecordsSafeFallbacksAndSearchDoesNotLeakHiddenListing() {
        Flow flow = createCompletedServiceFlow("fallback");
        post("/api/v1/ops/moderator-actions/hide-listing", Map.of(
                "targetType", "LISTING", "targetId", flow.listingId().toString(),
                "actor", "moderator@example.com", "reason", "Hide for fallback"), null);
        post("/api/v1/failure-matrix/run", Map.of(), null);
        assertThat(getList("/api/v1/failure-matrix").getBody().toString()).contains("POSTGRES_FALLBACK", "DUPLICATE_SAFE_REPLAY");
        assertThat(get("/api/v1/listings/search?query=fallback").getBody().toString()).doesNotContain(flow.listingId().toString());
    }
}
