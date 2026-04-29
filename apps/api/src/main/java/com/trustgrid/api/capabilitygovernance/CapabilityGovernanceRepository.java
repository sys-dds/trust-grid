package com.trustgrid.api.capabilitygovernance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.NotFoundException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CapabilityGovernanceRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CapabilityGovernanceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID createPolicy(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_capability_policies (
                    id, action_name, policy_name, policy_version, enabled, min_trust_tier,
                    required_verification_status, max_risk_level, requires_no_active_restriction,
                    requires_active_capability, max_value_cents, policy_json, created_by, reason
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, id, required(request, "actionName"), required(request, "policyName"),
                required(request, "policyVersion"), !Boolean.FALSE.equals(request.get("enabled")),
                optionalString(request.get("minTrustTier")), optionalString(request.get("requiredVerificationStatus")),
                optionalString(request.get("maxRiskLevel")),
                !Boolean.FALSE.equals(request.get("requiresNoActiveRestriction")),
                !Boolean.FALSE.equals(request.get("requiresActiveCapability")),
                optionalLong(request.get("maxValueCents")), json(request.getOrDefault("policy", Map.of())),
                required(request, "createdBy"), required(request, "reason"));
        return id;
    }

    List<Map<String, Object>> policies() {
        return jdbcTemplate.queryForList("select * from marketplace_capability_policies order by created_at desc");
    }

    Map<String, Object> policy(UUID id) {
        return jdbcTemplate.queryForList("select * from marketplace_capability_policies where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Capability policy not found"));
    }

    Optional<Map<String, Object>> policyFor(String actionName, String policyName, String policyVersion) {
        if (policyName != null && policyVersion != null) {
            return jdbcTemplate.queryForList("""
                    select * from marketplace_capability_policies
                    where action_name = ? and policy_name = ? and policy_version = ? and enabled = true
                    order by created_at desc limit 1
                    """, actionName, policyName, policyVersion).stream().findFirst();
        }
        return jdbcTemplate.queryForList("""
                select * from marketplace_capability_policies
                where action_name = ? and enabled = true
                order by created_at desc limit 1
                """, actionName).stream().findFirst();
    }

    Map<String, Object> participant(UUID participantId) {
        return jdbcTemplate.queryForList("""
                select p.id, p.account_status, p.verification_status, p.trust_tier, p.risk_level,
                       coalesce(tp.trust_score, 500) as trust_score,
                       coalesce(tp.trust_confidence, 0) as trust_confidence,
                       coalesce(tp.max_transaction_value_cents, 0) as max_transaction_value_cents
                from participants p
                left join trust_profiles tp on tp.participant_id = p.id
                where p.id = ?
                """, participantId).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Participant not found"));
    }

    List<String> activeCapabilities(UUID participantId) {
        return jdbcTemplate.queryForList("""
                select capability from participant_capabilities where participant_id = ? and status = 'ACTIVE'
                order by capability
                """, String.class, participantId);
    }

    List<Map<String, Object>> activeRestrictions(UUID participantId) {
        return jdbcTemplate.queryForList("""
                select id, restriction_type, max_transaction_value_cents, created_at
                from participant_restrictions
                where participant_id = ? and status = 'ACTIVE'
                order by created_at
                """, participantId);
    }

    Optional<Map<String, Object>> matchingTemporaryGrant(UUID participantId, String actionName, String targetType, UUID targetId) {
        return matching("temporary_capability_grants", participantId, actionName, targetType, targetId);
    }

    Optional<Map<String, Object>> matchingBreakGlass(UUID participantId, String actionName, String targetType, UUID targetId) {
        return matching("break_glass_capability_actions", participantId, actionName, targetType, targetId);
    }

    UUID insertDecision(UUID participantId, String actionName, String targetType, UUID targetId, String decision,
                        String policyName, String policyVersion, List<Map<String, Object>> denyReasons,
                        List<Map<String, Object>> nextSteps, Map<String, Object> inputSnapshot) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into capability_decision_logs (
                    id, participant_id, action_name, target_type, target_id, decision, policy_name, policy_version,
                    deny_reasons_json, next_steps_json, input_snapshot_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                """, id, participantId, actionName, targetType, targetId, decision, policyName, policyVersion,
                json(denyReasons), json(nextSteps), json(inputSnapshot));
        return id;
    }

    UUID createTemporaryGrant(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into temporary_capability_grants (
                    id, participant_id, action_name, target_type, target_id, status, granted_by, reason,
                    risk_acknowledgement, expires_at
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """, id, uuid(request, "participantId"), required(request, "actionName"),
                optionalString(request.get("targetType")), optionalUuid(request.get("targetId")),
                required(request, "grantedBy"), required(request, "reason"),
                required(request, "riskAcknowledgement"), Timestamp.from(instant(request, "expiresAt")));
        return id;
    }

    UUID createBreakGlass(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into break_glass_capability_actions (
                    id, participant_id, action_name, target_type, target_id, status, actor, reason,
                    risk_acknowledgement, expires_at
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """, id, uuid(request, "participantId"), required(request, "actionName"),
                optionalString(request.get("targetType")), optionalUuid(request.get("targetId")),
                required(request, "actor"), required(request, "reason"),
                required(request, "riskAcknowledgement"), Timestamp.from(instant(request, "expiresAt")));
        return id;
    }

    int revokeTemporaryGrant(UUID id, Map<String, Object> request) {
        return jdbcTemplate.update("""
                update temporary_capability_grants
                set status = 'REVOKED', revoked_at = now(), revoked_by = ?, revoke_reason = ?
                where id = ? and status = 'ACTIVE'
                """, required(request, "actor"), required(request, "reason"), id);
    }

    int revokeBreakGlass(UUID id, Map<String, Object> request) {
        return jdbcTemplate.update("""
                update break_glass_capability_actions
                set status = 'REVOKED', revoked_at = now(), revoked_by = ?, revoke_reason = ?
                where id = ? and status = 'ACTIVE'
                """, required(request, "actor"), required(request, "reason"), id);
    }

    List<Map<String, Object>> expireTemporaryGrants() {
        List<Map<String, Object>> expired = jdbcTemplate.queryForList("""
                update temporary_capability_grants
                set status = 'EXPIRED'
                where status = 'ACTIVE' and expires_at <= now()
                returning *
                """);
        return expired;
    }

    List<Map<String, Object>> expireBreakGlass() {
        return jdbcTemplate.queryForList("""
                update break_glass_capability_actions
                set status = 'EXPIRED'
                where status = 'ACTIVE' and expires_at <= now()
                returning *
                """);
    }

    List<Map<String, Object>> temporaryGrants() {
        return jdbcTemplate.queryForList("select * from temporary_capability_grants order by created_at desc");
    }

    Map<String, Object> temporaryGrant(UUID id) {
        return jdbcTemplate.queryForList("select * from temporary_capability_grants where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Temporary capability grant not found"));
    }

    List<Map<String, Object>> breakGlassActions() {
        return jdbcTemplate.queryForList("select * from break_glass_capability_actions order by created_at desc");
    }

    Map<String, Object> breakGlass(UUID id) {
        return jdbcTemplate.queryForList("select * from break_glass_capability_actions where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Break-glass capability action not found"));
    }

    void timeline(UUID participantId, String actionName, String targetType, UUID targetId, String eventType,
                  String actor, String reason, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into capability_governance_timeline_events (
                    id, participant_id, action_name, target_type, target_id, event_type, actor, reason, payload_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), participantId, actionName, targetType, targetId, eventType,
                actor, reason, json(payload));
    }

    List<Map<String, Object>> timeline(UUID participantId) {
        if (participantId == null) {
            return jdbcTemplate.queryForList("select * from capability_governance_timeline_events order by created_at desc limit 200");
        }
        return jdbcTemplate.queryForList("""
                select * from capability_governance_timeline_events
                where participant_id = ?
                order by created_at desc limit 200
                """, participantId);
    }

    Map<String, Object> targetSnapshot(String targetType, UUID targetId) {
        if (targetType == null || targetId == null) {
            return Map.of();
        }
        return switch (targetType) {
            case "LISTING" -> jdbcTemplate.queryForList("""
                    select l.id, l.status, l.price_amount_cents, l.budget_amount_cents, l.owner_participant_id,
                           coalesce(sd.searchable, false) as searchable
                    from marketplace_listings l
                    left join listing_search_documents sd on sd.listing_id = l.id
                    where l.id = ?
                    """, targetId).stream().findFirst().orElse(Map.of());
            case "TRANSACTION" -> jdbcTemplate.queryForList("""
                    select id, status, requester_participant_id, provider_participant_id, value_amount_cents,
                           exists (
                             select 1 from marketplace_disputes d
                             where d.transaction_id = marketplace_transactions.id
                               and d.status not in ('RESOLVED_BUYER','RESOLVED_SELLER','RESOLVED_PROVIDER','SPLIT_DECISION','CLOSED')
                           ) as unresolved_dispute
                    from marketplace_transactions
                    where id = ?
                    """, targetId).stream().findFirst().orElse(Map.of());
            default -> Map.of("targetId", targetId);
        };
    }

    private Optional<Map<String, Object>> matching(String table, UUID participantId, String actionName, String targetType, UUID targetId) {
        String sql = """
                select * from %s
                where participant_id = ? and action_name = ? and status = 'ACTIVE' and expires_at > now()
                  and (target_type is null or target_type = ?)
                  and (target_id is null or target_id = ?)
                order by created_at desc limit 1
                """.formatted(table);
        return jdbcTemplate.queryForList(sql, participantId, actionName, targetType, targetId).stream().findFirst();
    }

    String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    UUID uuid(Map<String, Object> request, String field) {
        return UUID.fromString(required(request, field));
    }

    UUID optionalUuid(Object value) {
        return value == null || value.toString().isBlank() ? null : UUID.fromString(value.toString());
    }

    private Instant instant(Map<String, Object> request, String field) {
        Instant value = Instant.parse(required(request, field));
        if (!value.isAfter(Instant.now())) {
            throw new IllegalArgumentException(field + " must be in the future");
        }
        return value;
    }

    private Long optionalLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private String optionalString(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
