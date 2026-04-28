package com.trustgrid.api.rebuild;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RebuildRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RebuildRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    List<UUID> participants() {
        return jdbcTemplate.query("select id from participants order by created_at", (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    int rebuildSearchDocuments() {
        jdbcTemplate.update("delete from listing_search_documents");
        return jdbcTemplate.update("""
                insert into listing_search_documents (
                    listing_id, owner_participant_id, listing_type, category_code, title, description,
                    price_amount_cents, budget_amount_cents, location_mode, status, risk_tier, searchable,
                    search_backend_status, indexed_at, document_json
                )
                select l.id, l.owner_participant_id, l.listing_type, c.code, l.title, l.description,
                       l.price_amount_cents, l.budget_amount_cents, l.location_mode, l.status, l.risk_tier,
                       l.status = 'LIVE' and p.account_status not in ('SUSPENDED', 'CLOSED', 'RESTRICTED')
                         and not exists (
                           select 1 from participant_restrictions r
                           where r.participant_id = p.id and r.status = 'ACTIVE'
                             and r.restriction_type in ('HIDDEN_FROM_MARKETPLACE_SEARCH', 'LISTING_BLOCKED')
                         ),
                       'POSTGRES_FALLBACK', now(), jsonb_build_object('rebuilt', true)
                from marketplace_listings l
                join marketplace_categories c on c.id = l.category_id
                join participants p on p.id = l.owner_participant_id
                """);
    }

    int verifyEvidence() {
        int findings = 0;
        findings += jdbcTemplate.update("""
                insert into consistency_findings (id, finding_type, target_type, target_id, severity, message)
                select gen_random_uuid(), 'EVIDENCE_REFERENCE_INVALID', target_type, target_id, 'HIGH', 'Evidence target could not be verified'
                from marketplace_evidence e
                where (target_type = 'TRANSACTION' and not exists (select 1 from marketplace_transactions t where t.id = e.target_id))
                   or (target_type = 'LISTING' and not exists (select 1 from marketplace_listings l where l.id = e.target_id))
                   or (target_type = 'DISPUTE' and not exists (select 1 from marketplace_disputes d where d.id = e.target_id))
                   or (target_type = 'PARTICIPANT' and not exists (select 1 from participants p where p.id = e.target_id))
                """);
        findings += jdbcTemplate.update("""
                insert into consistency_findings (id, finding_type, target_type, target_id, severity, message)
                select gen_random_uuid(), 'EVIDENCE_REFERENCE_INVALID', target_type, target_id, 'MEDIUM', 'Satisfied requirement references missing evidence'
                from evidence_requirements r
                where satisfied = true and not exists (select 1 from marketplace_evidence e where e.id = r.satisfied_by_evidence_id)
                """);
        return findings;
    }

    int replayOutbox() {
        return jdbcTemplate.update("""
                insert into marketplace_event_analytics (
                    id, source_event_id, aggregate_type, aggregate_id, event_type, occurred_at, payload_json
                )
                select gen_random_uuid(), id, aggregate_type, aggregate_id, event_type, created_at, payload_json
                from marketplace_events
                where event_status in ('PENDING', 'FAILED')
                on conflict do nothing
                """);
    }

    int replayTimeline() {
        return jdbcTemplate.update("""
                insert into consistency_findings (id, finding_type, target_type, target_id, severity, message)
                select gen_random_uuid(), 'TIMELINE_REPLAY_MISMATCH', 'TRANSACTION', t.id, 'MEDIUM', 'Completed transaction has no completion timeline event'
                from marketplace_transactions t
                where t.status = 'COMPLETED'
                  and not exists (
                    select 1 from transaction_timeline_events e
                    where e.transaction_id = t.id and e.event_type = 'TRANSACTION_COMPLETED'
                  )
                """);
    }

    UUID run(String type, String actor, String reason, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into rebuild_runs (id, rebuild_type, status, started_by, reason, completed_at, summary_json)
                values (?, ?, 'SUCCEEDED', ?, ?, now(), cast(? as jsonb))
                """, id, type, actor, reason, json(summary));
        return id;
    }

    List<Map<String, Object>> findings() {
        return jdbcTemplate.queryForList("select * from consistency_findings order by created_at desc");
    }

    Map<String, Object> run(UUID id) {
        return jdbcTemplate.queryForMap("select * from rebuild_runs where id = ?", id);
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }
}
