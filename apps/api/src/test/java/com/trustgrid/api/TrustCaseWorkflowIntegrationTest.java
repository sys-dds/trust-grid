package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustCaseWorkflowIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void caseAssignmentPlaybookEvidenceBundleRecommendationsAndMetricsWork() {
        UUID participant = createCapableParticipant("case-" + suffix(), "Case", "BUY");
        UUID caseId = openTrustCase(participant);
        post("/api/v1/trust-cases/" + caseId + "/assign", Map.of("assignedTo", "analyst@example.com", "actor", "operator@example.com", "reason", "Assign"), null);
        post("/api/v1/trust-cases/" + caseId + "/apply-playbook", Map.of("playbookKey", "review_abuse_triage", "actor", "operator@example.com", "reason", "Apply"), null);
        assertThat(get("/api/v1/trust-cases/" + caseId + "/evidence-bundle").getBody().toString()).contains(participant.toString(), "trust_case_targets_only");
        assertThat(getList("/api/v1/trust-cases/" + caseId + "/recommendations").getBody()).isNotEmpty();
        assertThat(get("/api/v1/trust-cases/metrics").getBody().get("openCases")).isNotNull();
    }
}
