package com.trustgrid.api.consistency;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsistencyRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConsistencyRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID run(String checkType, Map<String, Object> request) {
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into consistency_check_runs (id, check_type, status, requested_by, reason)
                values (?, ?, 'RUNNING', ?, ?)
                """, runId, checkType, actor(request), reason(request));
        int findings = switch (checkType) {
            case "TRUST_PROFILE" -> trustProfile();
            case "REPUTATION_REBUILD" -> reputation();
            case "SEARCH_INDEX" -> searchIndex();
            case "EVENT_ANALYTICS" -> analytics();
            case "EVIDENCE_REFERENCE" -> evidence();
            case "DISPUTE" -> dispute();
            case "CAPABILITY" -> capability();
            default -> trustProfile() + reputation() + searchIndex() + analytics() + evidence() + dispute() + capability();
        };
        Map<String, Object> summary = Map.of("findings", findings, "autoRepair", false);
        jdbcTemplate.update("""
                update consistency_check_runs set status = 'SUCCEEDED', completed_at = now(), summary_json = cast(? as jsonb)
                where id = ?
                """, json(summary), runId);
        return runId;
    }

    List<Map<String, Object>> runs() {
        return jdbcTemplate.queryForList("select * from consistency_check_runs order by started_at desc");
    }

    List<Map<String, Object>> findings() {
        return jdbcTemplate.queryForList("select * from consistency_findings order by created_at desc");
    }

    int trustProfile() {
        return insertFindings("""
                select participant_id as target_id, 'Suspended account cannot keep elevated trust profile' as message
                from trust_profiles tp join participants p on p.id = tp.participant_id
                where p.account_status in ('SUSPENDED','CLOSED') and tp.trust_tier in ('TRUSTED','HIGH_TRUST')
                """, "TRUST_PROFILE", "PARTICIPANT", "HIGH");
    }

    int reputation() {
        return insertFindings("""
                select rs.participant_id as target_id, 'Latest reputation snapshot drifts from trust profile' as message
                from reputation_snapshots rs
                join trust_profiles tp on tp.participant_id = rs.participant_id
                where rs.created_at = (select max(created_at) from reputation_snapshots latest where latest.participant_id = rs.participant_id)
                  and abs(rs.trust_score - tp.trust_score) > 100
                """, "REPUTATION_DRIFT", "PARTICIPANT", "MEDIUM");
    }

    int searchIndex() {
        return insertFindings("""
                select l.id as target_id, 'Search document visibility does not match listing/account truth' as message
                from marketplace_listings l
                join participants p on p.id = l.owner_participant_id
                left join listing_search_documents d on d.listing_id = l.id
                where (l.status <> 'LIVE' or p.account_status in ('SUSPENDED','CLOSED','RESTRICTED'))
                  and coalesce(d.searchable, false) = true
                """, "SEARCH_INDEX_DRIFT", "LISTING", "HIGH");
    }

    int analytics() {
        return insertFindings("""
                select e.id as target_id, 'Marketplace event missing analytics ingestion' as message
                from marketplace_events e
                left join marketplace_event_analytics a on a.source_event_id = e.id
                where a.id is null
                limit 25
                """, "ANALYTICS_MISSING_EVENT", "EVENT", "LOW");
    }

    int evidence() {
        return insertFindings("""
                select er.id as target_id, 'Satisfied evidence requirement references missing evidence' as message
                from evidence_requirements er
                left join marketplace_evidence e on e.id = er.satisfied_by_evidence_id
                where er.satisfied = true and e.id is null
                """, "EVIDENCE_REFERENCE_INVALID", "EVIDENCE", "HIGH");
    }

    int dispute() {
        return insertFindings("""
                select id as target_id, 'Resolved dispute is missing outcome or resolution reason' as message
                from marketplace_disputes
                where status in ('RESOLVED_BUYER','RESOLVED_SELLER','RESOLVED_PROVIDER','SPLIT_DECISION','CLOSED')
                  and (outcome is null or resolution_reason is null)
                """, "DISPUTE_INCONSISTENT", "DISPUTE", "MEDIUM");
    }

    int capability() {
        return insertFindings("""
                select pc.participant_id as target_id, 'Suspended or closed participant has active capability' as message
                from participant_capabilities pc
                join participants p on p.id = pc.participant_id
                where p.account_status in ('SUSPENDED','CLOSED') and pc.status = 'ACTIVE'
                """, "CAPABILITY_INCONSISTENT", "PARTICIPANT", "HIGH");
    }

    private int insertFindings(String selectSql, String type, String targetType, String severity) {
        return jdbcTemplate.update("""
                insert into consistency_findings (id, finding_type, target_type, target_id, severity, message, metadata_json)
                select gen_random_uuid(), ?, ?, target_id, ?, message, '{}'::jsonb
                from (
                """ + selectSql + """
                ) findings
                on conflict (finding_type, target_type, target_id) where status = 'OPEN' do update set
                    severity = excluded.severity,
                    message = excluded.message,
                    metadata_json = excluded.metadata_json,
                    last_seen_at = now()
                """, type, targetType, severity);
    }

    private String actor(Map<String, Object> request) {
        return request.getOrDefault("actor", request.getOrDefault("requestedBy", "operator@example.com")).toString();
    }

    private String reason(Map<String, Object> request) {
        return request.getOrDefault("reason", "Consistency check").toString();
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
