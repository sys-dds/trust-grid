package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class Tg161To180IntegrationTestSupport extends Tg101To160IntegrationTestSupport {

    UUID createPolicy(String name, String version, boolean requiresApproval) {
        ResponseEntity<Map> response = post("/api/v1/policies", Map.of(
                "policyName", name,
                "policyVersion", version,
                "policy", Map.of("requiresApproval", requiresApproval),
                "createdBy", "operator@example.com",
                "reason", "Policy proof"
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("policyId").toString());
    }

    UUID addRule(String policyName, String policyVersion, String key, String type, String scope,
                 Map<String, Object> condition, Map<String, Object> action, int priority) {
        ResponseEntity<Map> response = post("/api/v1/policy-engine/rules", Map.of(
                "policyName", policyName,
                "policyVersion", policyVersion,
                "ruleKey", key,
                "ruleType", type,
                "targetScope", scope,
                "condition", condition,
                "action", action,
                "priority", priority,
                "enabled", true
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("ruleId").toString());
    }

    Map<?, ?> evaluatePolicy(String policyName, String policyVersion, String targetType, UUID targetId,
                             Map<String, Object> input) {
        ResponseEntity<Map> response = post("/api/v1/policy-engine/evaluate", Map.of(
                "policyName", policyName,
                "policyVersion", policyVersion,
                "targetType", targetType,
                "targetId", targetId.toString(),
                "input", input
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    void approveAndActivate(UUID policyId) {
        post("/api/v1/policies/" + policyId + "/request-approval",
                Map.of("requestedBy", "operator@example.com", "reason", "Approval required"), null);
        post("/api/v1/policies/" + policyId + "/approve", Map.of(
                "approvedBy", "risk-lead@example.com",
                "reason", "Approved deterministic policy",
                "riskAcknowledgement", "Scoped deterministic control acknowledged"), null);
        ResponseEntity<Map> response = post("/api/v1/policies/" + policyId + "/activate", Map.of(), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    UUID requestAndApproveException(String policyName, String policyVersion, String targetType, UUID targetId,
                                    String exceptionType) {
        ResponseEntity<Map> created = post("/api/v1/policy-exceptions", Map.of(
                "policyName", policyName,
                "policyVersion", policyVersion,
                "targetType", targetType,
                "targetId", targetId.toString(),
                "exceptionType", exceptionType,
                "requestedBy", "operator@example.com",
                "reason", "Scoped exception proof",
                "expiresAt", "2026-12-31T00:00:00Z"
        ), null);
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        UUID exceptionId = UUID.fromString(created.getBody().get("id").toString());
        post("/api/v1/policy-exceptions/" + exceptionId + "/approve", Map.of(
                "approvedBy", "risk-lead@example.com",
                "reason", "Exception approved",
                "riskAcknowledgement", "This bypasses one deterministic policy control"), null);
        return exceptionId;
    }

    Map<String, Object> condition(String field, String operator, Object value) {
        return Map.of("conditions", java.util.List.of(Map.of("field", field, "operator", operator, "value", value)));
    }

    Map<String, Object> decision(String decision) {
        return Map.of("decision", decision);
    }
}
