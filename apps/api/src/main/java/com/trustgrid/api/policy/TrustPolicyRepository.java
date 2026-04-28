package com.trustgrid.api.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
