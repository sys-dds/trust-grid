package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustCaseMergeSplitReplayIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void mergeSplitAndReplayAreDeterministic() {
        UUID p = createCapableParticipant("case-merge-" + suffix(), "Case Merge", "BUY");
        UUID first = openTrustCase(p);
        UUID second = openTrustCase(p);
        assertThat(post("/api/v1/trust-cases/merge", Map.of("sourceCaseId", second.toString(), "targetCaseId", first.toString(), "actor", "operator@example.com", "reason", "Merge"), null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(post("/api/v1/trust-cases/" + first + "/split", Map.of("actor", "operator@example.com", "reason", "Split"), null).getBody().get("newCaseId")).isNotNull();
        assertThat(post("/api/v1/trust-cases/" + first + "/replay", Map.of(), null).getBody().get("deterministic")).isEqualTo(true);
    }
}
