package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class Tg241To360IntegrationTestSupport extends Tg221To240IntegrationTestSupport {

    UUID openTrustCase(UUID targetId) {
        ResponseEntity<Map> opened = post("/api/v1/trust-cases", Map.of(
                "caseType", "REVIEW_ABUSE",
                "priority", "HIGH",
                "title", "Review abuse proof " + suffix(),
                "summary", "Trust case integration proof",
                "openedBy", "operator@example.com",
                "reason", "Open trust case"
        ), null);
        assertThat(opened.getStatusCode().is2xxSuccessful()).isTrue();
        UUID caseId = UUID.fromString(opened.getBody().get("caseId").toString());
        post("/api/v1/trust-cases/" + caseId + "/targets", Map.of(
                "targetType", "PARTICIPANT",
                "targetId", targetId.toString(),
                "relationshipType", "PRIMARY",
                "actor", "operator@example.com",
                "reason", "Link case target"
        ), null);
        return caseId;
    }

    UUID createCampaign() {
        ResponseEntity<Map> response = post("/api/v1/trust-campaigns", Map.of(
                "campaignType", "REVIEW_RING",
                "severity", "HIGH",
                "title", "Campaign proof " + suffix(),
                "summary", "Campaign containment proof",
                "openedBy", "operator@example.com",
                "reason", "Create campaign"
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("campaignId").toString());
    }

    UUID createGuaranteePolicy() {
        ResponseEntity<Map> response = post("/api/v1/marketplace-guarantees/policies", Map.of(
                "policyName", "guarantee_policy",
                "policyVersion", "guarantee_policy_v1",
                "maxValueCents", 10000,
                "createdBy", "operator@example.com",
                "reason", "Guarantee policy proof"
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("policyId").toString());
    }

    UUID createEnforcementPolicy() {
        ResponseEntity<Map> response = post("/api/v1/enforcement/policies", Map.of(
                "policyName", "enforcement_policy",
                "policyVersion", "enforcement_policy_v1",
                "createdBy", "operator@example.com",
                "reason", "Enforcement policy proof"
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("policyId").toString());
    }

    UUID createScenario(String scenarioKey) {
        ResponseEntity<Map> response = post("/api/v1/adversarial/scenarios", Map.of(
                "scenarioKey", scenarioKey,
                "name", scenarioKey,
                "description", "Synthetic bounded attack scenario",
                "expectedControls", List.of("risk_gate", "case_review")
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("scenarioId").toString());
    }

    UUID runScenario(String scenarioKey) {
        createScenario(scenarioKey);
        ResponseEntity<Map> response = post("/api/v1/adversarial/attack-runs", Map.of(
                "scenarioKey", scenarioKey,
                "requestedBy", "operator@example.com",
                "reason", "Run synthetic scenario",
                "seed", Map.of("deterministic", true)
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("attackRunId").toString());
    }

    UUID createRecoveryPlan(UUID participantId) {
        ResponseEntity<Map> response = post("/api/v1/trust-recovery/plans", Map.of(
                "participantId", participantId.toString(),
                "openedBy", "operator@example.com",
                "reason", "Create recovery plan",
                "requiredMilestones", List.of("complete_verification")
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("planId").toString());
    }

    String past() {
        return Instant.now().minusSeconds(60).toString();
    }
}
