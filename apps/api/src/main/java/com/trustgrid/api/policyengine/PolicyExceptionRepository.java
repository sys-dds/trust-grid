package com.trustgrid.api.policyengine;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PolicyExceptionRepository {
    private final JdbcTemplate jdbcTemplate;

    public PolicyExceptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    UUID create(Map<String, Object> request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into policy_exceptions (
                    id, policy_name, policy_version, target_type, target_id, exception_type,
                    status, reason, requested_by, expires_at
                ) values (?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, cast(? as timestamptz))
                """, id, required(request, "policyName"), required(request, "policyVersion"),
                required(request, "targetType"), UUID.fromString(required(request, "targetId")),
                required(request, "exceptionType"), required(request, "reason"),
                required(request, "requestedBy"), required(request, "expiresAt"));
        return id;
    }

    void approve(UUID id, Map<String, Object> request) {
        jdbcTemplate.update("""
                update policy_exceptions
                set status = 'ACTIVE', approved_by = ?, approval_reason = ?,
                    risk_acknowledgement = ?, approved_at = now()
                where id = ? and status = 'REQUESTED'
                """, required(request, "approvedBy"), required(request, "reason"),
                required(request, "riskAcknowledgement"), id);
    }

    void reject(UUID id, Map<String, Object> request) {
        jdbcTemplate.update("""
                update policy_exceptions
                set status = 'REJECTED', approved_by = ?, approval_reason = ?,
                    risk_acknowledgement = ?, approved_at = now()
                where id = ? and status = 'REQUESTED'
                """, required(request, "approvedBy"), required(request, "reason"),
                required(request, "riskAcknowledgement"), id);
    }

    void revoke(UUID id, Map<String, Object> request) {
        jdbcTemplate.update("""
                update policy_exceptions
                set status = 'REVOKED', approved_by = ?, approval_reason = ?, revoked_at = now()
                where id = ? and status in ('ACTIVE', 'APPROVED')
                """, required(request, "actor"), required(request, "reason"), id);
    }

    Map<String, Object> get(UUID id) {
        return jdbcTemplate.queryForMap("select * from policy_exceptions where id = ?", id);
    }

    List<Map<String, Object>> list(String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.queryForList("select * from policy_exceptions order by created_at desc");
        }
        return jdbcTemplate.queryForList("select * from policy_exceptions where status = ? order by created_at desc", status);
    }

    String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
