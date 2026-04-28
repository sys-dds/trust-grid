package com.trustgrid.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalyticsRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    int ingestEvents() {
        int events = jdbcTemplate.update("""
                insert into marketplace_event_analytics (
                    id, source_event_id, aggregate_type, aggregate_id, event_type, occurred_at, payload_json
                )
                select gen_random_uuid(), id, aggregate_type, aggregate_id, event_type, created_at, payload_json
                from marketplace_events
                where not exists (select 1 from marketplace_event_analytics a where a.source_event_id = marketplace_events.id)
                on conflict do nothing
                """);
        jdbcTemplate.update("""
                insert into risk_decision_analytics (id, risk_decision_id, target_type, target_id, risk_level, decision, policy_version, occurred_at)
                select gen_random_uuid(), id, target_type, target_id, risk_level, decision, policy_version, created_at
                from risk_decisions
                where not exists (select 1 from risk_decision_analytics a where a.risk_decision_id = risk_decisions.id)
                """);
        jdbcTemplate.update("""
                insert into dispute_analytics (id, dispute_id, dispute_type, status, outcome, opened_at, resolved_at)
                select gen_random_uuid(), id, dispute_type, status, outcome, opened_at, resolved_at
                from marketplace_disputes
                where not exists (select 1 from dispute_analytics a where a.dispute_id = marketplace_disputes.id)
                """);
        jdbcTemplate.update("""
                insert into ranking_analytics (id, ranking_decision_id, policy_version, result_count, suppressed_count)
                select gen_random_uuid(), id, policy_version, jsonb_array_length(result_ids_json), 0
                from ranking_decision_logs
                where not exists (select 1 from ranking_analytics a where a.ranking_decision_id = ranking_decision_logs.id)
                """);
        return events;
    }

    int count(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    Map<String, Integer> grouped(String sql) {
        Map<String, Integer> values = new LinkedHashMap<>();
        jdbcTemplate.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> values.put(rs.getString(1), rs.getInt(2)));
        return values;
    }

    UUID rebuildRun(String type, String actor, String reason, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into rebuild_runs (id, rebuild_type, status, started_by, reason, completed_at, summary_json)
                values (?, ?, 'SUCCEEDED', ?, ?, now(), cast(? as jsonb))
                """, id, type, actor, reason, json(summary));
        return id;
    }

    void finding(String type, String targetType, UUID targetId, String severity, String message) {
        jdbcTemplate.update("""
                insert into consistency_findings (id, finding_type, target_type, target_id, severity, message)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), type, targetType, targetId, severity, message);
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }
}
