package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class Tg221To240IntegrationTestSupport extends Tg181To220IntegrationTestSupport {

    UUID createCapabilityPolicy(String actionName, Map<String, Object> overrides) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("actionName", actionName);
        body.put("policyName", "capability_policy");
        body.put("policyVersion", "capability_policy_v1");
        body.put("createdBy", "operator@example.com");
        body.put("reason", "Capability governance proof");
        body.put("requiresActiveCapability", true);
        body.put("requiresNoActiveRestriction", true);
        body.putAll(overrides);
        ResponseEntity<Map> response = post("/api/v1/capability-governance/policies", body, null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("policyId").toString());
    }

    Map<?, ?> simulateCapability(UUID participantId, String actionName, String targetType, UUID targetId, Long valueCents) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("participantId", participantId.toString());
        body.put("actionName", actionName);
        body.put("policyName", "capability_policy");
        body.put("policyVersion", "capability_policy_v1");
        body.put("actor", "operator@example.com");
        body.put("reason", "Capability simulation proof");
        if (targetType != null) {
            body.put("targetType", targetType);
        }
        if (targetId != null) {
            body.put("targetId", targetId.toString());
        }
        if (valueCents != null) {
            body.put("valueCents", valueCents);
        }
        ResponseEntity<Map> response = post("/api/v1/capability-governance/simulate", body, null);
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("capability simulation response: " + response.getBody())
                .isTrue();
        return response.getBody();
    }

    UUID temporaryGrant(UUID participantId, String actionName, String targetType, UUID targetId, String expiresAt) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("participantId", participantId.toString());
        body.put("actionName", actionName);
        body.put("grantedBy", "operator@example.com");
        body.put("reason", "Targeted temporary grant");
        body.put("riskAcknowledgement", "I understand this grants one scoped marketplace action");
        body.put("expiresAt", expiresAt);
        if (targetType != null) {
            body.put("targetType", targetType);
        }
        if (targetId != null) {
            body.put("targetId", targetId.toString());
        }
        ResponseEntity<Map> response = post("/api/v1/capability-governance/temporary-grants", body, null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("grantId").toString());
    }

    UUID breakGlass(UUID participantId, String actionName, String targetType, UUID targetId, String expiresAt) {
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("participantId", participantId.toString());
        body.put("actionName", actionName);
        body.put("actor", "senior-operator@example.com");
        body.put("reason", "Emergency scoped action");
        body.put("riskAcknowledgement", "I understand this is an audited emergency override");
        body.put("expiresAt", expiresAt);
        if (targetType != null) {
            body.put("targetType", targetType);
        }
        if (targetId != null) {
            body.put("targetId", targetId.toString());
        }
        ResponseEntity<Map> response = post("/api/v1/capability-governance/break-glass", body, null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("breakGlassId").toString());
    }

    void updateStatus(UUID participantId, String status) {
        post("/api/v1/participants/" + participantId + "/account-status", Map.of(
                "newStatus", status,
                "actor", "operator@example.com",
                "reason", "Capability governance status fixture"
        ), "status-" + participantId + "-" + status + "-" + suffix());
    }

    void verifyParticipant(UUID participantId) {
        post("/api/v1/participants/" + participantId + "/verification", Map.of(
                "newStatus", "VERIFIED",
                "actor", "operator@example.com",
                "reason", "Capability governance verification fixture"
        ), "verify-" + participantId + "-" + suffix());
    }

    void restrict(UUID participantId, String type) {
        post("/api/v1/participants/" + participantId + "/restrictions", Map.of(
                "restrictionType", type,
                "actor", "operator@example.com",
                "reason", "Capability governance restriction fixture"
        ), "restriction-" + participantId + "-" + type + "-" + suffix());
    }

    String future() {
        return Instant.now().plusSeconds(3600).toString();
    }
}
