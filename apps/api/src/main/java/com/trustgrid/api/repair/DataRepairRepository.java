package com.trustgrid.api.repair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataRepairRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DataRepairRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    int generate() {
        return jdbcTemplate.update("""
                insert into data_repair_recommendations (
                    id, consistency_finding_id, repair_type, target_type, target_id, severity, recommendation_json, reason
                )
                select gen_random_uuid(), f.id,
                       case
                         when f.finding_type in ('REPUTATION_DRIFT','TRUST_PROFILE') then 'REBUILD_REPUTATION'
                         when f.finding_type = 'SEARCH_INDEX_DRIFT' then 'REBUILD_SEARCH_INDEX'
                         when f.finding_type = 'EVIDENCE_REFERENCE_INVALID' then 'MARK_EVIDENCE_REFERENCE_INVALID'
                         when f.finding_type = 'ANALYTICS_MISSING_EVENT' then 'REPLAY_EVENTS'
                         else 'REQUEST_OPERATOR_REVIEW'
                       end,
                       f.target_type, f.target_id, f.severity,
                       jsonb_build_object('findingType', f.finding_type, 'message', f.message, 'autoFix', false),
                       'Recommendation generated from consistency finding'
                from consistency_findings f
                where f.status = 'OPEN'
                  and not exists (
                    select 1 from data_repair_recommendations r
                    where r.consistency_finding_id = f.id and r.status in ('PROPOSED','APPROVED','APPLIED')
                  )
                """);
    }

    List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("select * from data_repair_recommendations order by created_at desc");
    }

    Map<String, Object> get(UUID id) {
        return jdbcTemplate.queryForList("select * from data_repair_recommendations where id = ?", id).stream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Data repair recommendation not found"));
    }

    void approve(UUID id, Map<String, Object> request) {
        require(request, "actor");
        require(request, "reason");
        int updated = jdbcTemplate.update("""
                update data_repair_recommendations set status = 'APPROVED', decided_at = now()
                where id = ? and status = 'PROPOSED'
                """, id);
        if (updated == 0) {
            ensureExists(id);
            throw new ConflictException("Data repair recommendation cannot be approved from current state");
        }
    }

    UUID apply(UUID id, Map<String, Object> request) {
        Map<String, Object> before = get(id);
        require(request, "actor");
        require(request, "reason");
        require(request, "riskAcknowledgement");
        int updated = jdbcTemplate.update("""
                update data_repair_recommendations set status = 'APPLIED', decided_at = now()
                where id = ? and status in ('PROPOSED','APPROVED')
                """, id);
        if (updated == 0) {
            throw new ConflictException("Data repair recommendation cannot be applied from current state");
        }
        UUID findingId = (UUID) before.get("consistency_finding_id");
        if (findingId != null) {
            jdbcTemplate.update("update consistency_findings set status = 'RESOLVED', resolved_at = now() where id = ?", findingId);
        }
        UUID actionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into operator_data_repair_actions (
                    id, repair_recommendation_id, action_type, target_type, target_id, actor, reason,
                    risk_acknowledgement, before_json, after_json
                ) values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, actionId, id, actionType(before.get("repair_type").toString()), before.get("target_type"),
                before.get("target_id"), require(request, "actor"), require(request, "reason"),
                require(request, "riskAcknowledgement"), json(before), json(get(id)));
        return actionId;
    }

    UUID reject(UUID id, Map<String, Object> request) {
        Map<String, Object> before = get(id);
        require(request, "actor");
        require(request, "reason");
        int updated = jdbcTemplate.update("""
                update data_repair_recommendations set status = 'REJECTED', decided_at = now()
                where id = ? and status in ('PROPOSED','APPROVED')
                """, id);
        if (updated == 0) {
            throw new ConflictException("Data repair recommendation cannot be rejected from current state");
        }
        UUID actionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into operator_data_repair_actions (
                    id, repair_recommendation_id, action_type, target_type, target_id, actor, reason,
                    risk_acknowledgement, before_json, after_json
                ) values (?, ?, 'REJECT_RECOMMENDATION', ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))
                """, actionId, id, before.get("target_type"), before.get("target_id"), require(request, "actor"),
                require(request, "reason"), request.getOrDefault("riskAcknowledgement", "Repair rejected"),
                json(before), json(get(id)));
        return actionId;
    }

    List<Map<String, Object>> actions() {
        return jdbcTemplate.queryForList("select * from operator_data_repair_actions order by created_at desc");
    }

    private String actionType(String repairType) {
        return switch (repairType) {
            case "REBUILD_REPUTATION" -> "APPLY_REBUILD_REPUTATION";
            case "REBUILD_SEARCH_INDEX" -> "APPLY_REBUILD_SEARCH_INDEX";
            case "REBUILD_LINEAGE" -> "APPLY_REBUILD_LINEAGE";
            default -> "MARK_FINDING_RESOLVED";
        };
    }

    private void ensureExists(UUID id) {
        get(id);
    }

    private String require(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
