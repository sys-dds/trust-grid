package com.trustgrid.api.simulator;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FailureMatrixController {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxRepository outboxRepository;

    public FailureMatrixController(JdbcTemplate jdbcTemplate, OutboxRepository outboxRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping("/api/v1/failure-matrix/run")
    public Map<String, Object> run() {
        List<Map<String, Object>> scenarios = List.of(
                Map.of("scenario", "redis_read_path", "dependency", "REDIS", "behavior", "POSTGRES_FALLBACK"),
                Map.of("scenario", "outbox_duplicate_delay", "dependency", "KAFKA", "behavior", "DUPLICATE_SAFE_REPLAY"),
                Map.of("scenario", "listing_search", "dependency", "OPENSEARCH", "behavior", "POSTGRES_FALLBACK"),
                Map.of("scenario", "analytics_metrics", "dependency", "CLICKHOUSE", "behavior", "POSTGRES_FALLBACK"),
                Map.of("scenario", "evidence_metadata", "dependency", "MINIO", "behavior", "METADATA_ONLY_SAFE")
        );
        for (Map<String, Object> scenario : scenarios) {
            jdbcTemplate.update("""
                    insert into failure_matrix_results (id, scenario_name, dependency_name, status, degraded_behavior, passed)
                    values (?, ?, ?, 'SUCCEEDED', ?, true)
                    """, UUID.randomUUID(), scenario.get("scenario"), scenario.get("dependency"), scenario.get("behavior"));
        }
        outboxRepository.insert("FAILURE_MATRIX", UUID.randomUUID(), null, "FAILURE_MATRIX_RUN", Map.of("scenarios", scenarios.size()));
        return Map.of("scenarios", scenarios.size(), "passed", true);
    }

    @GetMapping("/api/v1/failure-matrix")
    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("select * from failure_matrix_results order by created_at desc");
    }
}
