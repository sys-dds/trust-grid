package com.trustgrid.api.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScamSimulatorRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ScamSimulatorRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    UUID run(String type, String requestedBy, String reason, int seedSize, Map<String, Object> summary) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into scam_simulation_runs (id, simulation_type, status, requested_by, reason, seed_size, summary_json, completed_at)
                values (?, ?, 'SUCCEEDED', ?, ?, ?, cast(? as jsonb), now())
                """, id, type, requestedBy, reason, seedSize, json(summary));
        return id;
    }

    UUID benchmark(String type, String requestedBy, String reason, Map<String, Object> result) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into benchmark_runs (id, benchmark_type, status, requested_by, reason, result_json, completed_at)
                values (?, ?, 'SUCCEEDED', ?, ?, cast(? as jsonb), now())
                """, id, type, requestedBy, reason, json(result));
        return id;
    }

    void syntheticCluster(String type, UUID id) {
        jdbcTemplate.update("""
                insert into review_abuse_clusters (
                    id, cluster_type, severity, summary, signals_json, member_participant_ids_json, review_ids_json
                ) values (?, 'SYNTHETIC_CLUSTER_SIGNAL', 'HIGH', ?, '["synthetic_campaign","deterministic_rules_v1"]'::jsonb, '[]'::jsonb, '[]'::jsonb)
                """, id, type + ":" + id);
    }

    int capped(Map<String, Object> request, String key, int max) {
        Object value = request.getOrDefault(key, 0);
        int requested = value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
        return Math.max(0, Math.min(requested, max));
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }
}
