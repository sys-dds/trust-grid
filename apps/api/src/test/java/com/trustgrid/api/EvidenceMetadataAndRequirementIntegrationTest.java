package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class EvidenceMetadataAndRequirementIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void evidenceMetadataAndRequirementSatisfactionWorkWithoutObjectWrite() {
        Flow flow = createDisputableServiceFlow("evidence");
        UUID disputeId = openDispute(flow, "evidence-dispute-" + suffix());
        UUID evidenceId = recordEvidence("DISPUTE", disputeId, flow.buyerId(), "USER_STATEMENT", "evidence-" + suffix());

        ResponseEntity<List> requirementsResponse = getList("/api/v1/evidence-requirements?targetType=DISPUTE&targetId=" + disputeId);
        assertThat(requirementsResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Map<?, ?> requirement = (Map<?, ?>) requirementsResponse.getBody().getFirst();

        ResponseEntity<Map> satisfied = post("/api/v1/evidence-requirements/" + requirement.get("requirementId") + "/satisfy", Map.of(
                "evidenceId", evidenceId.toString(),
                "actor", "participant@example.com",
                "reason", "Requirement satisfied"
        ), "satisfy-" + suffix());
        assertThat(satisfied.getBody().get("satisfied")).isEqualTo(true);
        assertThat(get("/api/v1/evidence/" + evidenceId).getBody().get("objectKey")).asString().startsWith("placeholder/");
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'EVIDENCE_REQUIREMENT_SATISFIED'")).isPositive();
    }
}
