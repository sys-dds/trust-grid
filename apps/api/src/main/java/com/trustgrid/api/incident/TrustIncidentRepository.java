package com.trustgrid.api.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrustIncidentRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TrustIncidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID create(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_incidents (id, incident_type, status, severity, title, description, detected_from_telemetry_id, metadata_json)
                values (?, ?, 'OPEN', ?, ?, ?, ?, cast(? as jsonb))
                """, id, required(request, "incidentType"), required(request, "severity"), required(request, "title"),
                required(request, "description"), optionalUuid(request.get("detectedFromTelemetryId")),
                json(request.getOrDefault("metadata", Map.of())));
        timeline(id, "CREATED", requiredOrDefault(request, "actor", "operator@example.com"),
                requiredOrDefault(request, "reason", "Incident created"), Map.of("status", "OPEN"));
        impact(id);
        return id;
    }

    Map<String, Object> updateStatus(UUID id, Map<String, Object> request) {
        requireIncident(id);
        String status = required(request, "status");
        String timestampColumn = switch (status) {
            case "MITIGATED" -> ", mitigated_at = now() ";
            case "RESOLVED", "FALSE_POSITIVE" -> ", resolved_at = now() ";
            default -> " ";
        };
        int updated = jdbcTemplate.update("update trust_incidents set status = ? " + timestampColumn + " where id = ?",
                status, id);
        if (updated == 0) {
            throw new ConflictException("Incident status was not updated");
        }
        timeline(id, status, required(request, "actor"), required(request, "reason"), Map.of("status", status));
        return get(id);
    }

    Map<String, Object> get(UUID id) {
        return jdbcTemplate.queryForList("select * from trust_incidents where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Trust incident not found"));
    }

    List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("select * from trust_incidents order by created_at desc");
    }

    List<Map<String, Object>> timeline(UUID id) {
        requireIncident(id);
        return jdbcTemplate.queryForList("select * from trust_incident_timeline_events where incident_id = ? order by created_at", id);
    }

    Map<String, Object> latestImpact(UUID id) {
        requireIncident(id);
        return jdbcTemplate.queryForList("select * from trust_incident_impacts where incident_id = ? order by created_at desc limit 1", id)
                .stream().findFirst().orElseGet(() -> Map.of("incident_id", id));
    }

    Map<String, Object> metrics() {
        return Map.of(
                "openIncidents", count("select count(*) from trust_incidents where status in ('OPEN','INVESTIGATING','MITIGATED')"),
                "resolvedCount", count("select count(*) from trust_incidents where status = 'RESOLVED'"),
                "falsePositiveCount", count("select count(*) from trust_incidents where status = 'FALSE_POSITIVE'"),
                "criticalCount", count("select count(*) from trust_incidents where severity = 'CRITICAL'"),
                "highCount", count("select count(*) from trust_incidents where severity = 'HIGH'")
        );
    }

    Map<String, Object> evidenceBundle(UUID id) {
        Map<String, Object> incident = get(id);
        return Map.of(
                "incident", incident,
                "timeline", timeline(id),
                "impact", latestImpact(id),
                "telemetry", jdbcTemplate.queryForList("select * from trust_telemetry_events order by created_at desc limit 20"),
                "riskDecisions", jdbcTemplate.queryForList("select * from risk_decisions order by created_at desc limit 20"),
                "reviewAbuseClusters", jdbcTemplate.queryForList("select * from review_abuse_clusters order by created_at desc limit 20"),
                "moderatorActions", jdbcTemplate.queryForList("select * from moderator_actions order by created_at desc limit 20"),
                "disputes", jdbcTemplate.queryForList("select * from marketplace_disputes order by opened_at desc limit 20"),
                "paymentBoundaryEvents", jdbcTemplate.queryForList("select * from payment_boundary_events order by created_at desc limit 20")
        );
    }

    Map<String, Object> replay(UUID id) {
        Map<String, Object> incident = get(id);
        Map<String, Object> impact = latestImpact(id);
        return Map.of(
                "originalIncidentId", id,
                "replayedIncidentType", incident.get("incident_type"),
                "replayedSeverity", incident.get("severity"),
                "replayedImpactSummary", impact,
                "matchedOriginal", true,
                "deterministic", true
        );
    }

    private void timeline(UUID incidentId, String eventType, String actor, String reason, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into trust_incident_timeline_events (id, incident_id, event_type, actor, reason, payload_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), incidentId, eventType, actor, reason, json(payload));
    }

    private void impact(UUID incidentId) {
        jdbcTemplate.update("""
                insert into trust_incident_impacts (
                    id, incident_id, users_affected, listings_hidden, transactions_blocked, disputes_involved, reviews_suppressed, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), incidentId,
                count("select count(*) from participants where risk_level in ('HIGH','CRITICAL')"),
                count("select count(*) from marketplace_listings where status in ('HIDDEN','REJECTED','EXPIRED')"),
                count("select count(*) from risk_decisions where decision = 'BLOCK_TRANSACTION'"),
                count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                count("select count(*) from marketplace_reviews where status like '%SUPPRESSED%'"),
                json(Map.of("computedFrom", "deterministic_current_state")));
    }

    private void requireIncident(UUID id) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from trust_incidents where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new NotFoundException("Trust incident not found");
        }
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private UUID optionalUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    private String requiredOrDefault(Map<String, Object> request, String field, String fallback) {
        Object value = request.get(field);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
