package com.trustgrid.api.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<IdempotencyRecordResponse> find(String scope, String key) {
        return jdbcTemplate.query("""
                select id, scope, idempotency_key, request_hash, resource_type, resource_id
                from idempotency_records
                where scope = ? and idempotency_key = ?
                """, (rs, rowNum) -> new IdempotencyRecordResponse(
                rs.getObject("id", UUID.class),
                rs.getString("scope"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class)
        ), scope, key).stream().findFirst();
    }

    public void insert(String scope, String key, String requestHash, String resourceType, UUID resourceId) {
        jdbcTemplate.update("""
                insert into idempotency_records (id, scope, idempotency_key, request_hash, resource_type, resource_id)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), scope, key, requestHash, resourceType, resourceId);
    }
}
