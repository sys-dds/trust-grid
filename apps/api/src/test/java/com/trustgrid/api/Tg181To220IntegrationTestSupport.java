package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

abstract class Tg181To220IntegrationTestSupport extends Tg161To180IntegrationTestSupport {

    Map<String, Object> operator() {
        return Map.of("requestedBy", "operator@example.com", "reason", "Operational proof");
    }

    Map<String, Object> actorRisk() {
        return Map.of("actor", "operator@example.com", "reason", "Reviewed operational action",
                "riskAcknowledgement", "I understand this changes trust-derived state");
    }

    UUID createRiskPolicyWithRule(String version, String ruleKey, String ruleType, String scope,
                                  Map<String, Object> condition, String decision) {
        UUID policyId = createPolicy("risk_policy", version, true);
        addRule("risk_policy", version, ruleKey, ruleType, scope, condition, decision(decision), 10);
        approveAndActivate(policyId);
        return policyId;
    }

    UUID createIncident() {
        ResponseEntity<Map> response = post("/api/v1/trust-incidents", Map.of(
                "incidentType", "RISK_SPIKE",
                "severity", "HIGH",
                "title", "Risk spike proof",
                "description", "Risk spike detected from deterministic telemetry",
                "actor", "operator@example.com",
                "reason", "Incident proof",
                "metadata", Map.of("source", "test")
        ), null);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return UUID.fromString(response.getBody().get("id").toString());
    }

    UUID firstIdFromList(String path, String idField) {
        List body = getList(path).getBody();
        assertThat(body).isNotEmpty();
        return UUID.fromString(((Map<?, ?>) body.getFirst()).get(idField).toString());
    }
}
