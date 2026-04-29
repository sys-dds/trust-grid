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
            default -> trustProfile() + reputation() + searchIndex() + analytics() + evidence() + dispute() + capability()
                    + finalTrustSafety();
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
        int findings = insertFindings("""
                select pc.participant_id as target_id, 'Suspended or closed participant has active capability' as message
                from participant_capabilities pc
                join participants p on p.id = pc.participant_id
                where p.account_status in ('SUSPENDED','CLOSED') and pc.status = 'ACTIVE'
                """, "CAPABILITY_INCONSISTENT", "PARTICIPANT", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Active temporary capability grant is past expiry' as message
                from temporary_capability_grants
                where status = 'ACTIVE' and expires_at <= now()
                """, "EXPIRED_TEMPORARY_GRANT_STILL_ACTIVE", "TEMPORARY_CAPABILITY_GRANT", "MEDIUM");
        findings += insertFindings("""
                select id as target_id, 'Active break-glass capability action is past expiry' as message
                from break_glass_capability_actions
                where status = 'ACTIVE' and expires_at <= now()
                """, "EXPIRED_BREAK_GLASS_STILL_ACTIVE", "BREAK_GLASS_CAPABILITY_ACTION", "HIGH");
        findings += insertFindings("""
                select d.id as target_id, 'Capability decision replay marker indicates mismatch review is required' as message
                from capability_decision_logs d
                where d.input_snapshot_json->>'forceReplayMismatch' = 'true'
                """, "CAPABILITY_DECISION_REPLAY_MISMATCH", "CAPABILITY_DECISION", "MEDIUM");
        findings += insertFindings("""
                select t.id as target_id, 'Capability governance timeline references a missing participant' as message
                from capability_governance_timeline_events t
                left join participants p on p.id = t.participant_id
                where p.id is null
                """, "CAPABILITY_TIMELINE_TARGET_MISSING", "CAPABILITY_GOVERNANCE_TIMELINE", "HIGH");
        findings += insertFindings("""
                select g.id as target_id, 'Active temporary capability grant exists for a closed participant' as message
                from temporary_capability_grants g
                join participants p on p.id = g.participant_id
                where g.status = 'ACTIVE' and p.account_status = 'CLOSED'
                """, "ACTIVE_GRANT_FOR_CLOSED_PARTICIPANT", "TEMPORARY_CAPABILITY_GRANT", "HIGH");
        findings += insertFindings("""
                select b.id as target_id, 'Active break-glass capability action exists for a closed participant' as message
                from break_glass_capability_actions b
                join participants p on p.id = b.participant_id
                where b.status = 'ACTIVE' and p.account_status = 'CLOSED'
                """, "ACTIVE_BREAK_GLASS_FOR_CLOSED_PARTICIPANT", "BREAK_GLASS_CAPABILITY_ACTION", "HIGH");
        return findings;
    }

    int finalTrustSafety() {
        int findings = insertFindings("""
                select t.id as target_id, 'Trust case target references a missing source record' as message
                from trust_case_targets t
                where t.target_type = 'PARTICIPANT'
                  and not exists (select 1 from participants p where p.id = t.target_id)
                """, "TRUST_CASE_TARGET_INVALID", "TRUST_CASE_TARGET", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Assigned trust case is missing assignee' as message
                from trust_cases
                where status = 'ASSIGNED' and assigned_to is null
                """, "TRUST_CASE_ASSIGNEE_MISSING", "TRUST_CASE", "MEDIUM");
        findings += insertFindings("""
                select id as target_id, 'Trust case SLA is overdue and unresolved' as message
                from trust_cases
                where sla_due_at < now() and status not in ('RESOLVED','FALSE_POSITIVE','CANCELLED')
                """, "TRUST_CASE_SLA_OVERDUE", "TRUST_CASE", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Campaign graph edge references missing campaign' as message
                from trust_campaign_graph_edges e
                where not exists (select 1 from trust_campaigns c where c.id = e.campaign_id)
                """, "CAMPAIGN_GRAPH_INVALID", "CAMPAIGN_GRAPH_EDGE", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Evidence versions are not sequential' as message
                from evidence_versions ev
                where version_number <= 0
                """, "EVIDENCE_VERSION_SEQUENCE_INVALID", "EVIDENCE_VERSION", "MEDIUM");
        findings += insertFindings("""
                select id as target_id, 'Evidence custody hash mismatch requires review' as message
                from evidence_custody_events
                where event_type = 'HASH_MISMATCH_DETECTED'
                """, "EVIDENCE_HASH_MISMATCH", "EVIDENCE", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Guarantee decision references a missing transaction' as message
                from guarantee_decision_logs g
                where transaction_id is not null and not exists (select 1 from marketplace_transactions t where t.id = g.transaction_id)
                """, "GUARANTEE_REFERENCE_INVALID", "GUARANTEE_DECISION", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Severe enforcement action has no approval signal' as message
                from enforcement_actions
                where action_type in ('SUSPEND_ACCOUNT','PERMANENT_REMOVAL_RECOMMENDED') and risk_acknowledgement is null
                """, "ENFORCEMENT_APPROVAL_MISSING", "ENFORCEMENT_ACTION", "HIGH");
        findings += insertFindings("""
                select id as target_id, 'Adversarial run has no expected controls recorded' as message
                from adversarial_attack_runs r
                where not exists (select 1 from detection_coverage_matrix m where m.attack_run_id = r.id and m.expected = true)
                """, "ADVERSARIAL_COVERAGE_MISSING", "ATTACK_RUN", "MEDIUM");
        findings += insertFindings("""
                select id as target_id, 'Trust dossier snapshot target requires review' as message
                from trust_dossier_snapshots
                where target_id is null
                """, "TRUST_DOSSIER_TARGET_INVALID", "TRUST_DOSSIER", "LOW");
        return findings;
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
