package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DisputeResolutionTrustImpactIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void disputeResolutionRequiresEvidenceAndAppliesTrustImpactWithoutMoneyEvents() {
        Flow flow = createDisputableServiceFlow("dispute-resolution");
        UUID disputeId = openDispute(flow, "resolution-open-" + suffix());
        assertThat(post("/api/v1/disputes/" + disputeId + "/resolve", Map.of(
                "outcome", "BUYER_WINS",
                "resolvedBy", "moderator@example.com",
                "reason", "Evidence supports buyer"
        ), null).getStatusCode().value()).isEqualTo(409);

        UUID evidenceId = recordEvidence("DISPUTE", disputeId, flow.buyerId(), "USER_STATEMENT", "resolution-evidence-" + suffix());
        Map<?, ?> requirement = (Map<?, ?>) getList("/api/v1/evidence-requirements?targetType=DISPUTE&targetId=" + disputeId).getBody().getFirst();
        post("/api/v1/evidence-requirements/" + requirement.get("requirementId") + "/satisfy", Map.of(
                "evidenceId", evidenceId.toString(),
                "actor", "participant@example.com",
                "reason", "Evidence satisfies dispute"
        ), "resolution-satisfy-" + suffix());

        Map<?, ?> resolved = post("/api/v1/disputes/" + disputeId + "/resolve", Map.of(
                "outcome", "BUYER_WINS",
                "resolvedBy", "moderator@example.com",
                "reason", "Evidence supports buyer"
        ), null).getBody();
        assertThat(resolved.get("status")).isEqualTo("RESOLVED_BUYER");
        assertThat(countRows("select count(*) from reputation_recalculation_events")).isGreaterThanOrEqualTo(2);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'DISPUTE_RESOLVED'")).isPositive();
    }
}
