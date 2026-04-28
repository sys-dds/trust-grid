package com.trustgrid.api.observability;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrustTelemetryService {
    private final TrustTelemetryRepository repository;
    private final OutboxRepository outboxRepository;

    public TrustTelemetryService(TrustTelemetryRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> record(Map<String, Object> request) {
        UUID id = repository.record(required(request, "telemetryType"), required(request, "targetType"),
                optionalUuid(request.get("targetId")), required(request, "severity"),
                number(request.get("signalValue")), number(request.get("thresholdValue")),
                request.get("policyVersion") == null ? null : request.get("policyVersion").toString(),
                payload(request));
        outboxRepository.insert("TRUST_TELEMETRY", id, null, "TRUST_TELEMETRY_RECORDED",
                Map.of("telemetryType", request.get("telemetryType"), "severity", request.get("severity")));
        return Map.of("telemetryId", id, "status", "RECORDED");
    }

    @Transactional
    public Map<String, Object> createSlo(Map<String, Object> request) {
        UUID id = repository.createSlo(request);
        return Map.of("sloId", id, "status", "CREATED");
    }

    public List<Map<String, Object>> slos() {
        return repository.slos();
    }

    public List<Map<String, Object>> telemetry(String type) {
        return repository.telemetry(type);
    }

    @Transactional
    public Map<String, Object> evaluateSlos() {
        List<Map<String, Object>> breaches = new ArrayList<>();
        for (Map<String, Object> slo : repository.enabledSlos()) {
            String targetType = slo.get("target_type").toString();
            int current = repository.countForTarget(targetType);
            Number threshold = (Number) slo.get("threshold_value");
            if (current >= threshold.doubleValue()) {
                UUID telemetryId = repository.record(targetType, targetType, null, slo.get("severity").toString(),
                        current, threshold, "deterministic_rules_v1", Map.of("sloKey", slo.get("slo_key")));
                UUID alertId = repository.createAlert(null, slo.get("severity").toString(),
                        "Trust SLO breached: " + slo.get("slo_key"));
                outboxRepository.insert("TRUST_SLO", telemetryId, null, "TRUST_SLO_BREACHED",
                        Map.of("sloKey", slo.get("slo_key"), "signalValue", current));
                outboxRepository.insert("TRUST_ALERT", alertId, null, "TRUST_ALERT_CREATED",
                        Map.of("sloKey", slo.get("slo_key")));
                breaches.add(Map.of("sloKey", slo.get("slo_key"), "telemetryId", telemetryId, "alertId", alertId));
            }
        }
        return Map.of("evaluated", repository.enabledSlos().size(), "breaches", breaches);
    }

    @Transactional
    public Map<String, Object> runMonitors(Map<String, Object> request) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String type : List.of("MODERATION_BACKLOG", "DISPUTE_BACKLOG", "RISK_SPIKE", "REVIEW_ABUSE_SPIKE",
                "TRUST_SCORE_SPIKE", "SEARCH_SUPPRESSION_SPIKE")) {
            int signal = repository.countForTarget(type);
            int threshold = type.equals("TRUST_SCORE_SPIKE") ? 1 : 0;
            String severity = signal >= threshold ? "HIGH" : "LOW";
            UUID telemetryId = repository.record(type, type, null, severity, signal, threshold,
                    "deterministic_rules_v1", Map.of("requestedBy", request.getOrDefault("requestedBy", "operator@example.com")));
            outboxRepository.insert("TRUST_TELEMETRY", telemetryId, null, "TRUST_TELEMETRY_RECORDED",
                    Map.of("telemetryType", type, "severity", severity));
            UUID incidentId = null;
            UUID alertId = null;
            if (List.of("HIGH", "CRITICAL").contains(severity)) {
                incidentId = repository.createIncident(type, severity, telemetryId, type + " detected",
                        "Manual trust monitor detected " + type, Map.of("signalValue", signal, "thresholdValue", threshold));
                alertId = repository.createAlert(incidentId, severity, "Trust monitor requires operator review: " + type);
                outboxRepository.insert("TRUST_INCIDENT", incidentId, null, "TRUST_INCIDENT_CREATED", Map.of("telemetryType", type));
                outboxRepository.insert("TRUST_ALERT", alertId, null, "TRUST_ALERT_CREATED", Map.of("incidentId", incidentId));
            }
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("telemetryType", type);
            result.put("telemetryId", telemetryId);
            result.put("severity", severity);
            result.put("signalValue", signal);
            result.put("incidentId", incidentId);
            result.put("alertId", alertId);
            results.add(result);
        }
        return Map.of("monitorRun", "SUCCEEDED", "results", results, "noBackgroundScheduler", true, "externalAlerting", false);
    }

    @Transactional
    public Map<String, Object> acknowledgeAlert(UUID id, Map<String, Object> request) {
        repository.acknowledgeAlert(id, request);
        outboxRepository.insert("TRUST_ALERT", id, null, "TRUST_ALERT_ACKNOWLEDGED", Map.of("alertId", id));
        return repository.alert(id);
    }

    public List<Map<String, Object>> alerts() {
        return repository.alerts();
    }

    @Transactional
    public Map<String, Object> dashboard() {
        Map<String, Object> dashboard = repository.dashboard();
        outboxRepository.insert("OPS_DASHBOARD", UUID.randomUUID(), null, "OPS_DASHBOARD_AGGREGATED", dashboard);
        return dashboard;
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> request) {
        Object payload = request.get("payload");
        return payload instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private UUID optionalUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
    }

    private Number number(Object value) {
        return value instanceof Number number ? number : null;
    }
}
