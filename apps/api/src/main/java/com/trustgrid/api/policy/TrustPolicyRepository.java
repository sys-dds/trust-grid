package com.trustgrid.api.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrustPolicyRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TrustPolicyRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID create(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_policy_versions (id, policy_name, policy_version, status, policy_json, created_by, reason)
                values (?, ?, ?, 'DRAFT', cast(? as jsonb), ?, ?)
                """, id, required(request, "policyName"), required(request, "policyVersion"),
                json(request.getOrDefault("policy", Map.of())), required(request, "createdBy"), required(request, "reason"));
        return id;
    }

    void activate(UUID id) {
        Map<String, Object> policy = jdbcTemplate.queryForMap("select policy_name from trust_policy_versions where id = ?", id);
        jdbcTemplate.update("update trust_policy_versions set status = 'RETIRED', retired_at = now() where policy_name = ? and status = 'ACTIVE'",
                policy.get("policy_name"));
        jdbcTemplate.update("update trust_policy_versions set status = 'ACTIVE', activated_at = now() where id = ?", id);
    }

    Map<String, Object> policy(UUID id) {
        return jdbcTemplate.queryForList("select * from trust_policy_versions where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Trust policy not found"));
    }

    boolean risky(UUID id) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from trust_policy_versions p
                where p.id = ?
                  and (p.policy_name in ('risk_policy', 'restriction_policy')
                    or p.policy_json::text like '%requiresApproval%'
                    or exists (
                        select 1 from trust_policy_rules r
                        where r.policy_version_id = p.id
                          and r.action_json::text ~ '(BLOCK_TRANSACTION|SUSPEND_ACCOUNT|RESTRICT_CAPABILITY|HIDE_LISTING|SUPPRESS_REVIEW_WEIGHT)'
                    ))
                """, Integer.class, id);
        return count != null && count > 0;
    }

    boolean approved(UUID id) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from policy_approvals where policy_version_id = ? and approval_status = 'APPROVED'
                """, Integer.class, id);
        return count != null && count > 0;
    }

    UUID requestApproval(UUID id, Map<String, Object> request) {
        requirePolicyExists(id);
        Integer existing = jdbcTemplate.queryForObject("""
                select count(*) from policy_approvals where policy_version_id = ? and approval_status = 'REQUIRED'
                """, Integer.class, id);
        if (existing != null && existing > 0) {
            throw new ConflictException("Policy approval request already exists");
        }
        UUID approvalId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into policy_approvals (id, policy_version_id, approval_status, requested_by, request_reason)
                values (?, ?, 'REQUIRED', ?, ?)
                """, approvalId, id, required(request, "requestedBy"), required(request, "reason"));
        return approvalId;
    }

    void approve(UUID id, Map<String, Object> request) {
        requirePolicyExists(id);
        int updated = jdbcTemplate.update("""
                update policy_approvals
                set approval_status = 'APPROVED', approved_by = ?, approval_reason = ?,
                    risk_acknowledgement = ?, decided_at = now()
                where policy_version_id = ? and approval_status = 'REQUIRED'
                """, required(request, "approvedBy"), required(request, "reason"),
                required(request, "riskAcknowledgement"), id);
        if (updated == 0) {
            throw new ConflictException("Policy approval cannot be approved from current state");
        }
    }

    void reject(UUID id, Map<String, Object> request) {
        requirePolicyExists(id);
        int updated = jdbcTemplate.update("""
                update policy_approvals
                set approval_status = 'REJECTED', approved_by = ?, approval_reason = ?,
                    risk_acknowledgement = ?, decided_at = now()
                where policy_version_id = ? and approval_status = 'REQUIRED'
                """, required(request, "approvedBy"), required(request, "reason"),
                required(request, "riskAcknowledgement"), id);
        if (updated == 0) {
            throw new ConflictException("Policy approval cannot be rejected from current state");
        }
    }

    UUID restorePrevious(UUID id) {
        requirePolicyExists(id);
        Map<String, Object> policy = policy(id);
        List<UUID> previousRows = jdbcTemplate.query("""
                select id from trust_policy_versions
                where policy_name = ? and status = 'RETIRED' and id <> ?
                order by retired_at desc nulls last, created_at desc
                limit 1
                """, (rs, rowNum) -> rs.getObject("id", UUID.class), policy.get("policy_name"), id);
        if (previousRows.isEmpty()) {
            throw new ConflictException("No previous active policy is available for rollback");
        }
        UUID previous = previousRows.getFirst();
        jdbcTemplate.update("update trust_policy_versions set status = 'RETIRED', retired_at = now() where id = ?", id);
        jdbcTemplate.update("update trust_policy_versions set status = 'ACTIVE', activated_at = now(), retired_at = null where id = ?", previous);
        return previous;
    }

    UUID blastRadius(Map<String, Object> request, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into policy_blast_radius_previews (
                    id, policy_name, from_policy_version, to_policy_version, requested_by, reason,
                    affected_users, affected_listings, affected_transactions, affected_disputes, summary_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, id, required(request, "policyName"), request.get("fromPolicyVersion"),
                required(request, "toPolicyVersion"), required(request, "requestedBy"), required(request, "reason"),
                ((Number) summary.getOrDefault("affectedUsers", 0)).intValue(),
                ((Number) summary.getOrDefault("affectedListings", 0)).intValue(),
                ((Number) summary.getOrDefault("affectedTransactions", 0)).intValue(),
                ((Number) summary.getOrDefault("affectedDisputes", 0)).intValue(),
                json(summary));
        return id;
    }

    Map<String, Object> policyDataCounts() {
        return Map.of(
                "affectedUsers", count("select count(*) from participants where trust_tier in ('NEW','LIMITED') or account_status in ('RESTRICTED','SUSPENDED')"),
                "affectedListings", count("select count(*) from marketplace_listings where status = 'LIVE' and (risk_tier in ('HIGH','RESTRICTED') or coalesce(price_amount_cents, budget_amount_cents, 0) >= 100000)"),
                "affectedTransactions", count("select count(*) from marketplace_transactions where status not in ('CANCELLED','COMPLETED') and value_amount_cents >= 50000"),
                "affectedDisputes", count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                "newUsersImpacted", count("select count(*) from participants where trust_tier = 'NEW'"),
                "wouldHideListings", count("select count(*) from marketplace_listings where risk_tier = 'RESTRICTED'"),
                "wouldBlockTransactions", count("select count(*) from marketplace_transactions where value_amount_cents >= 100000"),
                "wouldRequireEvidence", count("select count(*) from evidence_requirements where satisfied = false")
        );
    }

    int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    void retire(UUID id) {
        jdbcTemplate.update("update trust_policy_versions set status = 'RETIRED', retired_at = now() where id = ?", id);
    }

    UUID simulation(String type, Map<String, Object> request, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into policy_simulation_runs (
                    id, simulation_type, policy_name, from_policy_version, to_policy_version, status, requested_by, reason, summary_json, completed_at
                ) values (?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, cast(? as jsonb), now())
                """, id, type, request.getOrDefault("policyName", "risk_policy"), request.get("fromPolicyVersion"),
                request.getOrDefault("toPolicyVersion", "simulation_v1"), request.getOrDefault("requestedBy", "operator@example.com"),
                request.getOrDefault("reason", "Policy simulation"), json(summary));
        return id;
    }

    List<Map<String, Object>> policies() {
        return jdbcTemplate.queryForList("select * from trust_policy_versions order by created_at desc");
    }

    List<Map<String, Object>> active() {
        return jdbcTemplate.queryForList("select * from trust_policy_versions where status = 'ACTIVE' order by policy_name");
    }

    List<Map<String, Object>> simulations() {
        return jdbcTemplate.queryForList("select * from policy_simulation_runs order by created_at desc");
    }

    List<Map<String, Object>> abuseCampaigns() {
        return jdbcTemplate.queryForList("""
                select id, cluster_type as campaign_type, severity, signals_json, member_participant_ids_json, review_ids_json, created_at
                from review_abuse_clusters
                union all
                select id, 'OFF_PLATFORM_CONTACT', status, '[]'::jsonb, jsonb_build_array(reporter_participant_id::text, reported_participant_id::text), '[]'::jsonb, created_at
                from off_platform_contact_reports
                order by created_at desc
                """);
    }

    Map<String, Object> retentionSummary() {
        return Map.of(
                "evidence", "metadata_retention",
                "disputes", "case_retention",
                "reviews", "trust_signal_retention",
                "riskDecisions", "policy_audit_retention",
                "events", "outbox_audit_retention",
                "deletionJob", false
        );
    }

    String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private void requirePolicyExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from trust_policy_versions where id = ?", Integer.class, id);
        if (count == null || count == 0) {
            throw new NotFoundException("Trust policy not found");
        }
    }
}
