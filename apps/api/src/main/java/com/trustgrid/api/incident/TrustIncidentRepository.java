package com.trustgrid.api.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        UUID telemetryId = (UUID) incident.get("detected_from_telemetry_id");
        String incidentType = incident.get("incident_type").toString();
        Timestamp createdAt = (Timestamp) incident.get("created_at");
        Map<String, Object> telemetry = telemetryId == null ? Map.of() : telemetry(telemetryId);
        String telemetryType = telemetry.isEmpty() ? incidentType : telemetry.get("telemetry_type").toString();
        List<Map<String, Object>> includedSources = new ArrayList<>();
        includedSources.add(Map.of("source", "incident", "reason", "Requested incident"));
        includedSources.add(Map.of("source", "timeline", "reason", "Timeline scoped by incident id"));
        includedSources.add(Map.of("source", "impact", "reason", "Impact scoped by incident id"));
        if (!telemetry.isEmpty()) {
            includedSources.add(Map.of("source", "telemetry", "reason", "Detected telemetry and nearby same-type telemetry"));
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("incident", incident);
        bundle.put("timeline", timeline(id));
        bundle.put("impact", latestImpact(id));
        bundle.put("detectedTelemetry", telemetry);
        bundle.put("nearbyTelemetry", nearbyTelemetry(telemetryType, createdAt));
        bundle.put("riskDecisions", List.of());
        bundle.put("reviewAbuseClusters", List.of());
        bundle.put("moderatorActions", nearbyModeratorActions(createdAt));
        bundle.put("disputes", List.of());
        bundle.put("paymentBoundaryEvents", List.of());
        if ("RISK_SPIKE".equals(incidentType)) {
            bundle.put("riskDecisions", jdbcTemplate.queryForList("""
                    select * from risk_decisions
                    where risk_level in ('HIGH','CRITICAL')
                      and created_at between ? - interval '2 hours' and ? + interval '2 hours'
                    order by created_at desc limit 50
                    """, createdAt, createdAt));
            includedSources.add(Map.of("source", "riskDecisions", "reason", "Risk-spike incident type"));
        }
        if ("REVIEW_ABUSE_CAMPAIGN".equals(incidentType)) {
            bundle.put("reviewAbuseClusters", jdbcTemplate.queryForList("""
                    select * from review_abuse_clusters
                    where severity in ('HIGH','CRITICAL') or status in ('OPEN','ESCALATED','SUPPRESSED')
                    order by created_at desc limit 50
                    """));
            includedSources.add(Map.of("source", "reviewAbuseClusters", "reason", "Review-abuse incident type"));
        }
        if (List.of("DISPUTE_BACKLOG", "SAFETY_ESCALATION_CLUSTER", "EVIDENCE_BACKLOG").contains(incidentType)) {
            bundle.put("disputes", jdbcTemplate.queryForList("""
                    select * from marketplace_disputes
                    where status in ('OPEN','UNDER_REVIEW','ESCALATED','AWAITING_BUYER_EVIDENCE','AWAITING_SELLER_EVIDENCE','AWAITING_PROVIDER_EVIDENCE')
                    order by opened_at desc limit 50
                    """));
            includedSources.add(Map.of("source", "disputes", "reason", "Dispute, safety, or evidence incident type"));
        }
        if ("PAYMENT_BOUNDARY_ANOMALY".equals(incidentType)) {
            bundle.put("paymentBoundaryEvents", jdbcTemplate.queryForList("""
                    select * from payment_boundary_events
                    where created_at between ? - interval '2 hours' and ? + interval '2 hours'
                    order by created_at desc limit 50
                    """, createdAt, createdAt));
            includedSources.add(Map.of("source", "paymentBoundaryEvents", "reason", "Payment-boundary incident type"));
        }
        bundle.put("includedSources", includedSources);
        bundle.put("scope", "incident_related_records_only");
        return bundle;
    }

    Map<String, Object> replay(UUID id) {
        Map<String, Object> incident = get(id);
        Map<String, Object> originalImpact = latestImpact(id);
        UUID telemetryId = (UUID) incident.get("detected_from_telemetry_id");
        Map<String, Object> telemetry = telemetryId == null ? Map.of() : telemetry(telemetryId);
        String replayedType = telemetry.isEmpty()
                ? incident.get("incident_type").toString()
                : incidentType(telemetry.get("telemetry_type").toString());
        String replayedSeverity = telemetry.isEmpty()
                ? incident.get("severity").toString()
                : severityFromSignal(telemetry.get("telemetry_type").toString(), telemetry.get("signal_value"));
        Map<String, Object> replayedImpact = impactSummary(replayedType);
        List<String> mismatchReasons = new ArrayList<>();
        compare("incidentType", incident.get("incident_type"), replayedType, mismatchReasons);
        compare("severity", incident.get("severity"), replayedSeverity, mismatchReasons);
        compareImpact(originalImpact, replayedImpact, mismatchReasons);
        return Map.of(
                "originalIncidentId", id,
                "replayedIncidentType", replayedType,
                "replayedSeverity", replayedSeverity,
                "replayedImpactSummary", replayedImpact,
                "originalImpactSummary", originalImpact,
                "matchedOriginal", mismatchReasons.isEmpty(),
                "mismatchReasons", mismatchReasons,
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
        Map<String, Object> incident = get(incidentId);
        Map<String, Object> impact = impactSummary(incident.get("incident_type").toString());
        jdbcTemplate.update("""
                insert into trust_incident_impacts (
                    id, incident_id, users_affected, listings_hidden, transactions_blocked, disputes_involved, reviews_suppressed, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), incidentId, impact.get("users_affected"), impact.get("listings_hidden"),
                impact.get("transactions_blocked"), impact.get("disputes_involved"), impact.get("reviews_suppressed"),
                json(Map.of("computedFrom", "deterministic_source_records", "incidentType", incident.get("incident_type"))));
    }

    private Map<String, Object> telemetry(UUID telemetryId) {
        return jdbcTemplate.queryForList("select * from trust_telemetry_events where id = ?", telemetryId).stream()
                .findFirst()
                .orElse(Map.of());
    }

    private List<Map<String, Object>> nearbyTelemetry(String telemetryType, Timestamp createdAt) {
        if (telemetryType == null || createdAt == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                select * from trust_telemetry_events
                where telemetry_type = ?
                  and created_at between ? - interval '2 hours' and ? + interval '2 hours'
                order by created_at desc limit 50
                """, telemetryType, createdAt, createdAt);
    }

    private List<Map<String, Object>> nearbyModeratorActions(Timestamp createdAt) {
        if (createdAt == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                select * from moderator_actions
                where created_at between ? - interval '2 hours' and ? + interval '2 hours'
                order by created_at desc limit 50
                """, createdAt, createdAt);
    }

    private Map<String, Object> impactSummary(String incidentType) {
        return switch (incidentType) {
            case "RISK_SPIKE" -> Map.of(
                    "users_affected", count("select count(distinct target_id) from risk_decisions where target_type = 'PARTICIPANT' and risk_level in ('HIGH','CRITICAL')"),
                    "listings_hidden", count("select count(*) from marketplace_listings where status = 'HIDDEN'"),
                    "transactions_blocked", count("select count(*) from risk_decisions where decision = 'BLOCK_TRANSACTION'"),
                    "disputes_involved", 0,
                    "reviews_suppressed", 0
            );
            case "REVIEW_ABUSE_CAMPAIGN" -> Map.of(
                    "users_affected", count("select count(distinct reviewed_participant_id) from marketplace_reviews where status like '%SUPPRESSED%'"),
                    "listings_hidden", 0,
                    "transactions_blocked", 0,
                    "disputes_involved", 0,
                    "reviews_suppressed", count("select count(*) from marketplace_reviews where status like '%SUPPRESSED%'")
            );
            case "DISPUTE_BACKLOG", "SAFETY_ESCALATION_CLUSTER", "EVIDENCE_BACKLOG" -> Map.of(
                    "users_affected", count("select count(distinct opened_by_participant_id) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                    "listings_hidden", 0,
                    "transactions_blocked", 0,
                    "disputes_involved", count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                    "reviews_suppressed", 0
            );
            case "SEARCH_SUPPRESSION_ANOMALY" -> Map.of(
                    "users_affected", count("select count(distinct owner_participant_id) from listing_search_documents where searchable = false"),
                    "listings_hidden", count("select count(*) from listing_search_documents where searchable = false"),
                    "transactions_blocked", 0,
                    "disputes_involved", 0,
                    "reviews_suppressed", 0
            );
            default -> Map.of(
                    "users_affected", count("select count(*) from participants where risk_level in ('HIGH','CRITICAL') or account_status in ('RESTRICTED','SUSPENDED')"),
                    "listings_hidden", count("select count(*) from marketplace_listings where status in ('HIDDEN','REJECTED','EXPIRED')"),
                    "transactions_blocked", count("select count(*) from risk_decisions where decision = 'BLOCK_TRANSACTION'"),
                    "disputes_involved", count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                    "reviews_suppressed", count("select count(*) from marketplace_reviews where status like '%SUPPRESSED%'")
            );
        };
    }

    private String incidentType(String telemetryType) {
        return switch (telemetryType) {
            case "REVIEW_ABUSE_SPIKE" -> "REVIEW_ABUSE_CAMPAIGN";
            case "TRUST_SCORE_SPIKE" -> "TRUST_SCORE_ANOMALY";
            case "SEARCH_SUPPRESSION_SPIKE" -> "SEARCH_SUPPRESSION_ANOMALY";
            case "PAYMENT_BOUNDARY_REVIEW_SPIKE" -> "PAYMENT_BOUNDARY_ANOMALY";
            default -> telemetryType;
        };
    }

    private String severityFromSignal(String telemetryType, Object value) {
        int signal = value instanceof Number number ? number.intValue() : 0;
        Thresholds thresholds = thresholds(telemetryType);
        if (signal >= thresholds.critical()) {
            return "CRITICAL";
        }
        if (signal >= thresholds.high()) {
            return "HIGH";
        }
        if (signal >= thresholds.medium()) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private Thresholds thresholds(String telemetryType) {
        return switch (telemetryType) {
            case "DISPUTE_BACKLOG", "RISK_SPIKE" -> new Thresholds(3, 10, 25);
            case "REVIEW_ABUSE_SPIKE" -> new Thresholds(1, 3, 10);
            case "TRUST_SCORE_SPIKE" -> new Thresholds(1, 5, 15);
            case "MODERATION_BACKLOG", "SEARCH_SUPPRESSION_SPIKE" -> new Thresholds(5, 20, 50);
            default -> new Thresholds(5, 20, 50);
        };
    }

    private void compare(String field, Object original, Object replayed, List<String> mismatchReasons) {
        if (!Objects.equals(String.valueOf(original), String.valueOf(replayed))) {
            mismatchReasons.add(field + " changed from " + original + " to " + replayed);
        }
    }

    private void compareImpact(Map<String, Object> original, Map<String, Object> replayed, List<String> mismatchReasons) {
        for (String key : List.of("users_affected", "listings_hidden", "transactions_blocked", "disputes_involved", "reviews_suppressed")) {
            int originalValue = original.get(key) instanceof Number number ? number.intValue() : 0;
            int replayedValue = replayed.get(key) instanceof Number number ? number.intValue() : 0;
            if (originalValue != replayedValue) {
                mismatchReasons.add(key + " changed from " + originalValue + " to " + replayedValue);
            }
        }
    }

    private record Thresholds(int medium, int high, int critical) {
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
