package com.trustgrid.api.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(String aggregateType, UUID aggregateId, UUID participantId, String eventType, Map<String, Object> payload) {
        jdbcTemplate.update("""
                insert into marketplace_events (
                    id, aggregate_type, aggregate_id, participant_id, event_key, event_type, payload_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """, UUID.randomUUID(), aggregateType, aggregateId, participantId,
                eventType + ":" + aggregateId + ":" + UUID.randomUUID(), eventType, json(payload));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }
}
