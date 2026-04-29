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
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE = new TypeReference<>() {
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
                        List<Map<String, Object>> nextSteps, Map<String, Object> inputSnapshot,
                        Map<String, Object> policySnapshot) {
        UUID id = UUID.randomUUID();
        String policyJson = json(policySnapshot);
        jdbcTemplate.update("""
                insert into capability_decision_logs (
                    id, participant_id, action_name, target_type, target_id, decision, policy_name, policy_version,
                    deny_reasons_json, next_steps_json, input_snapshot_json, policy_snapshot_json, policy_hash
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                          cast(? as jsonb), ?)
                """, id, participantId, actionName, targetType, targetId, decision, policyName, policyVersion,
                json(denyReasons), json(nextSteps), json(inputSnapshot), policyJson, Integer.toHexString(policyJson.hashCode()));
        return id;
    }

    Map<String, Object> decision(UUID id) {
        return jdbcTemplate.queryForList("select * from capability_decision_logs where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Capability decision not found"));
    }

    List<UUID> candidateParticipants(List<?> requestedIds) {
        if (requestedIds != null && !requestedIds.isEmpty()) {
            return requestedIds.stream()
                    .map(Object::toString)
                    .map(UUID::fromString)
                    .limit(100)
                    .toList();
        }
        return jdbcTemplate.queryForList("select id from participants order by created_at desc limit 100", UUID.class);
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

    Map<String, Object> auditBundle(UUID participantId) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("participant", participant(participantId));
        bundle.put("activeCapabilities", activeCapabilities(participantId));
        bundle.put("activeRestrictions", activeRestrictions(participantId));
        bundle.put("recentCapabilityDecisions", jdbcTemplate.queryForList("""
                select * from capability_decision_logs
                where participant_id = ?
                order by created_at desc limit 25
                """, participantId));
        bundle.put("temporaryGrants", jdbcTemplate.queryForList("""
                select * from temporary_capability_grants
                where participant_id = ?
                order by created_at desc limit 25
                """, participantId));
        bundle.put("breakGlassActions", jdbcTemplate.queryForList("""
                select * from break_glass_capability_actions
                where participant_id = ?
                order by created_at desc limit 25
                """, participantId));
        bundle.put("governanceTimeline", timeline(participantId));
        bundle.put("relatedMarketplaceEvents", jdbcTemplate.queryForList("""
                select * from marketplace_events
                where participant_id = ? and event_type in (
                    'CAPABILITY_POLICY_CREATED', 'CAPABILITY_SIMULATED', 'CAPABILITY_DECISION_LOGGED',
                    'CAPABILITY_DECISION_REPLAYED', 'CAPABILITY_BLAST_RADIUS_PREVIEWED',
                    'TEMPORARY_CAPABILITY_GRANT_CREATED', 'TEMPORARY_CAPABILITY_GRANT_REVOKED',
                    'TEMPORARY_CAPABILITY_GRANT_EXPIRED', 'BREAK_GLASS_CAPABILITY_CREATED',
                    'BREAK_GLASS_CAPABILITY_REVOKED', 'BREAK_GLASS_CAPABILITY_EXPIRED'
                )
                order by created_at desc limit 50
                """, participantId));
        bundle.put("consistencyFindings", jdbcTemplate.queryForList("""
                select f.* from consistency_findings f
                left join temporary_capability_grants g
                  on f.target_type = 'TEMPORARY_CAPABILITY_GRANT' and f.target_id = g.id
                left join break_glass_capability_actions b
                  on f.target_type = 'BREAK_GLASS_CAPABILITY_ACTION' and f.target_id = b.id
                left join capability_decision_logs d
                  on f.target_type = 'CAPABILITY_DECISION' and f.target_id = d.id
                left join capability_governance_timeline_events t
                  on f.target_type = 'CAPABILITY_GOVERNANCE_TIMELINE' and f.target_id = t.id
                where f.target_id = ?
                   or g.participant_id = ?
                   or b.participant_id = ?
                   or d.participant_id = ?
                   or t.participant_id = ?
                order by f.created_at desc limit 50
                """, participantId, participantId, participantId, participantId, participantId));
        bundle.put("repairRecommendations", jdbcTemplate.queryForList("""
                select r.* from data_repair_recommendations r
                left join consistency_findings f on f.id = r.consistency_finding_id
                left join temporary_capability_grants g
                  on coalesce(r.target_type, f.target_type) = 'TEMPORARY_CAPABILITY_GRANT'
                 and coalesce(r.target_id, f.target_id) = g.id
                left join break_glass_capability_actions b
                  on coalesce(r.target_type, f.target_type) = 'BREAK_GLASS_CAPABILITY_ACTION'
                 and coalesce(r.target_id, f.target_id) = b.id
                left join capability_decision_logs d
                  on coalesce(r.target_type, f.target_type) = 'CAPABILITY_DECISION'
                 and coalesce(r.target_id, f.target_id) = d.id
                left join capability_governance_timeline_events t
                  on coalesce(r.target_type, f.target_type) = 'CAPABILITY_GOVERNANCE_TIMELINE'
                 and coalesce(r.target_id, f.target_id) = t.id
                where r.target_id = ? or f.target_id = ?
                   or g.participant_id = ?
                   or b.participant_id = ?
                   or d.participant_id = ?
                   or t.participant_id = ?
                order by r.created_at desc limit 50
                """, participantId, participantId, participantId, participantId, participantId, participantId));
        bundle.put("scope", "participant_capability_governance");
        return bundle;
    }

    Map<String, Object> dashboard() {
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("policiesEnabled", count("select count(*) from marketplace_capability_policies where enabled = true"));
        dashboard.put("decisionsLast24h", count("select count(*) from capability_decision_logs where created_at >= now() - interval '24 hours'"));
        dashboard.put("deniedLast24h", count("select count(*) from capability_decision_logs where created_at >= now() - interval '24 hours' and decision in ('DENY','REQUIRE_VERIFICATION','REQUIRE_EVIDENCE','REQUIRE_MANUAL_REVIEW')"));
        dashboard.put("temporaryGrantsActive", count("select count(*) from temporary_capability_grants where status = 'ACTIVE' and expires_at > now()"));
        dashboard.put("temporaryGrantsExpiredButNotMarked", count("select count(*) from temporary_capability_grants where status = 'ACTIVE' and expires_at <= now()"));
        dashboard.put("breakGlassActive", count("select count(*) from break_glass_capability_actions where status = 'ACTIVE' and expires_at > now()"));
        dashboard.put("breakGlassExpiredButNotMarked", count("select count(*) from break_glass_capability_actions where status = 'ACTIVE' and expires_at <= now()"));
        dashboard.put("topDeniedActions", jdbcTemplate.queryForList("""
                select action_name, count(*) as count from capability_decision_logs
                where created_at >= now() - interval '24 hours' and decision <> 'ALLOW'
                group by action_name order by count desc limit 10
                """));
        dashboard.put("topDenyReasons", jdbcTemplate.queryForList("""
                select reason->>'code' as code, count(*) as count
                from capability_decision_logs, jsonb_array_elements(deny_reasons_json) reason
                where created_at >= now() - interval '24 hours'
                group by reason->>'code' order by count desc limit 10
                """));
        dashboard.put("participantsWithActiveRestrictions", count("select count(distinct participant_id) from participant_restrictions where status = 'ACTIVE'"));
        dashboard.put("capabilityGovernanceOpenConsistencyFindings", count("""
                select count(*) from consistency_findings
                where status = 'OPEN' and (finding_type like 'CAPABILITY_%' or finding_type like 'EXPIRED_%' or finding_type like 'ACTIVE_%')
                """));
        dashboard.put("capabilityGovernanceOpenRepairRecommendations", count("""
                select count(*) from data_repair_recommendations
                where status in ('PROPOSED','APPROVED') and repair_type in (
                    'EXPIRE_TEMPORARY_CAPABILITY_GRANT','EXPIRE_BREAK_GLASS_CAPABILITY_ACTION',
                    'REQUEST_CAPABILITY_DECISION_REVIEW','REVOKE_GRANT_FOR_CLOSED_PARTICIPANT',
                    'REVOKE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT'
                )
                """));
        dashboard.put("deterministic", true);
        return dashboard;
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

    List<Map<String, Object>> readList(String value) {
        try {
            return objectMapper.readValue(value, LIST_TYPE);
        } catch (Exception exception) {
            return List.of();
        }
    }

    int count(String sql, Object... args) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
