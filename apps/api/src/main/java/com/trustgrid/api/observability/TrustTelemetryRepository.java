package com.trustgrid.api.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrustTelemetryRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TrustTelemetryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID record(String telemetryType, String targetType, UUID targetId, String severity,
                Number signalValue, Number thresholdValue, String policyVersion, Map<String, Object> payload) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_telemetry_events (
                    id, telemetry_type, target_type, target_id, severity, signal_value, threshold_value, policy_version, payload_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, telemetryType, targetType, targetId, severity, toDecimal(signalValue), toDecimal(thresholdValue),
                policyVersion, json(payload == null ? Map.of() : payload));
        return id;
    }

    UUID createSlo(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_slo_definitions (id, slo_key, name, target_type, threshold_value, window_minutes, severity, enabled)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, required(request, "sloKey"), required(request, "name"), required(request, "targetType"),
                new BigDecimal(required(request, "thresholdValue")),
                Integer.parseInt(required(request, "windowMinutes")),
                required(request, "severity"), !Boolean.FALSE.equals(request.get("enabled")));
        return id;
    }

    List<Map<String, Object>> slos() {
        return jdbcTemplate.queryForList("select * from trust_slo_definitions order by created_at desc");
    }

    List<Map<String, Object>> telemetry(String type) {
        if (type == null || type.isBlank()) {
            return jdbcTemplate.queryForList("select * from trust_telemetry_events order by created_at desc limit 100");
        }
        return jdbcTemplate.queryForList("select * from trust_telemetry_events where telemetry_type = ? order by created_at desc limit 100", type);
    }

    List<Map<String, Object>> enabledSlos() {
        return jdbcTemplate.queryForList("select * from trust_slo_definitions where enabled = true order by created_at");
    }

    int countForTarget(String targetType) {
        return switch (targetType) {
            case "MODERATION_BACKLOG" -> count("select count(*) from marketplace_ops_queue_items where status in ('OPEN','IN_REVIEW','AWAITING_EVIDENCE','ESCALATED')");
            case "DISPUTE_BACKLOG" -> count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED','AWAITING_BUYER_EVIDENCE','AWAITING_SELLER_EVIDENCE','AWAITING_PROVIDER_EVIDENCE')");
            case "RISK_SPIKE" -> count("select count(*) from risk_decisions where risk_level in ('HIGH','CRITICAL')");
            case "REVIEW_ABUSE_SPIKE" -> count("select count(*) from review_abuse_clusters where status = 'OPEN' and severity in ('HIGH','CRITICAL')");
            case "TRUST_SCORE_SPIKE" -> count("select count(*) from reputation_recalculation_events where abs(new_score - coalesce(previous_score, new_score)) >= 100");
            case "SEARCH_SUPPRESSION_SPIKE" -> count("select count(*) from listing_search_documents where searchable = false");
            case "EVIDENCE_BACKLOG" -> count("select count(*) from evidence_requirements where satisfied = false");
            case "PAYMENT_BOUNDARY_REVIEW_SPIKE" -> count("select count(*) from marketplace_ops_queue_items where queue_type = 'PAYMENT_BOUNDARY_REVIEW' and status = 'OPEN'");
            default -> count("select count(*) from trust_telemetry_events");
        };
    }

    UUID createIncident(String type, String severity, UUID telemetryId, String title, String description,
                        Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_incidents (id, incident_type, status, severity, title, description, detected_from_telemetry_id, metadata_json)
                values (?, ?, 'OPEN', ?, ?, ?, ?, cast(? as jsonb))
                """, id, incidentType(type), severity, title, description, telemetryId, json(metadata));
        jdbcTemplate.update("""
                insert into trust_incident_timeline_events (id, incident_id, event_type, actor, reason, payload_json)
                values (?, ?, 'DETECTED', 'system', 'Incident created from trust telemetry', cast(? as jsonb))
                """, UUID.randomUUID(), id, json(metadata));
        insertImpact(id);
        return id;
    }

    UUID createAlert(UUID incidentId, String severity, String message) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_alerts (id, incident_id, alert_type, severity, status, message)
                values (?, ?, 'INTERNAL_TRUST_ALERT', ?, 'OPEN', ?)
                """, id, incidentId, severity, message);
        return id;
    }

    Map<String, Object> alert(UUID id) {
        return jdbcTemplate.queryForList("select * from trust_alerts where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Trust alert not found"));
    }

    int acknowledgeAlert(UUID id, Map<String, Object> request) {
        int updated = jdbcTemplate.update("""
                update trust_alerts set status = 'ACKNOWLEDGED', acknowledged_at = now(), acknowledged_by = ?, acknowledgement_reason = ?
                where id = ? and status = 'OPEN'
                """, required(request, "actor"), required(request, "reason"), id);
        if (updated == 0) {
            Integer exists = jdbcTemplate.queryForObject("select count(*) from trust_alerts where id = ?", Integer.class, id);
            if (exists == null || exists == 0) {
                throw new NotFoundException("Trust alert not found");
            }
            throw new ConflictException("Trust alert cannot be acknowledged from current state");
        }
        return updated;
    }

    List<Map<String, Object>> alerts() {
        return jdbcTemplate.queryForList("select * from trust_alerts order by created_at desc");
    }

    Map<String, Object> dashboard() {
        return Map.of(
                "openIncidents", count("select count(*) from trust_incidents where status in ('OPEN','INVESTIGATING','MITIGATED')"),
                "openAlerts", count("select count(*) from trust_alerts where status = 'OPEN'"),
                "moderationBacklog", countForTarget("MODERATION_BACKLOG"),
                "disputeBacklog", countForTarget("DISPUTE_BACKLOG"),
                "evidenceBacklog", countForTarget("EVIDENCE_BACKLOG"),
                "reviewAbuseClusters", count("select count(*) from review_abuse_clusters where status = 'OPEN'"),
                "highRiskDecisions", count("select count(*) from risk_decisions where risk_level in ('HIGH','CRITICAL')"),
                "paymentBoundaryReviewBacklog", countForTarget("PAYMENT_BOUNDARY_REVIEW_SPIKE"),
                "openAppeals", count("select count(*) from appeals where status in ('OPEN','UNDER_REVIEW','EVIDENCE_REQUIRED')"),
                "recentPolicySimulationCount", count("select count(*) from policy_simulation_runs where created_at > now() - interval '1 day'")
        );
    }

    private void insertImpact(UUID incidentId) {
        Map<String, Object> incident = jdbcTemplate.queryForMap("select incident_type from trust_incidents where id = ?", incidentId);
        Map<String, Object> impact = impactSummary(incident.get("incident_type").toString());
        jdbcTemplate.update("""
                insert into trust_incident_impacts (
                    id, incident_id, users_affected, listings_hidden, transactions_blocked, disputes_involved, reviews_suppressed, metadata_json
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), incidentId, impact.get("users_affected"), impact.get("listings_hidden"),
                impact.get("transactions_blocked"), impact.get("disputes_involved"), impact.get("reviews_suppressed"),
                json(Map.of("computedFrom", "deterministic_source_records", "incidentType", incident.get("incident_type"))));
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

    int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private BigDecimal toDecimal(Number number) {
        return number == null ? null : new BigDecimal(number.toString());
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
