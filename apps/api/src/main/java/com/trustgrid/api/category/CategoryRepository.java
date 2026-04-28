package com.trustgrid.api.category;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryRepository {

    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CategoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<CategoryResponse> findEnabled() {
        return jdbcTemplate.query("""
                select id, code, name, description, default_risk_tier, allowed_listing_types_json,
                       evidence_requirement_hint, enabled, created_at, updated_at
                from marketplace_categories
                where enabled = true
                order by code
                """, this::row);
    }

    public Optional<CategoryResponse> findByCode(String code) {
        return jdbcTemplate.query("""
                select id, code, name, description, default_risk_tier, allowed_listing_types_json,
                       evidence_requirement_hint, enabled, created_at, updated_at
                from marketplace_categories
                where code = ? and enabled = true
                """, this::row, code).stream().findFirst();
    }

    private CategoryResponse row(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryResponse(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                CategoryRiskTier.valueOf(rs.getString("default_risk_tier")),
                readAllowed(rs.getString("allowed_listing_types_json")),
                rs.getString("evidence_requirement_hint"),
                rs.getBoolean("enabled"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private List<ListingType> readAllowed(String value) {
        try {
            return objectMapper.readValue(value, LIST_TYPE).stream().map(ListingType::valueOf).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }
}
