package com.trustgrid.api.reviewgraph;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewGraphRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReviewGraphRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    int rebuildEdges() {
        return jdbcTemplate.update("""
                insert into review_graph_edges (
                    id, review_id, transaction_id, reviewer_participant_id, reviewed_participant_id,
                    rating, transaction_value_cents, normalized_text_hash
                )
                select gen_random_uuid(), r.id, r.transaction_id, r.reviewer_participant_id, r.reviewed_participant_id,
                       r.overall_rating, t.value_amount_cents,
                       md5(lower(regexp_replace(coalesce(r.review_text, ''), '\\s+', ' ', 'g')))
                from marketplace_reviews r
                join marketplace_transactions t on t.id = r.transaction_id
                where not exists (select 1 from review_graph_edges e where e.review_id = r.id)
                """);
    }

    List<ReviewGraphEdgeResponse> edges() {
        return jdbcTemplate.query("""
                select id, review_id, transaction_id, reviewer_participant_id, reviewed_participant_id,
                       rating, transaction_value_cents, normalized_text_hash, created_at
                from review_graph_edges order by created_at desc
                """, this::edgeRow);
    }

    UUID createCluster(String type, String severity, String summary, List<String> signals,
                       List<UUID> members, List<UUID> reviews) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into review_abuse_clusters (
                    id, cluster_type, severity, summary, signals_json, member_participant_ids_json, review_ids_json
                ) values (?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                """, id, type, severity, summary, json(signals), json(members), json(reviews));
        return id;
    }

    boolean clusterExists(String type, String summary) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from review_abuse_clusters where cluster_type = ? and summary = ? and status in ('OPEN', 'SUPPRESSED', 'ESCALATED')
                """, Integer.class, type, summary);
        return count != null && count > 0;
    }

    List<Map<String, Object>> reciprocalPairs() {
        return jdbcTemplate.queryForList("""
                select a.reviewer_participant_id as a_id, a.reviewed_participant_id as b_id,
                       array_agg(distinct a.review_id::text) || array_agg(distinct b.review_id::text) as reviews
                from review_graph_edges a
                join review_graph_edges b on b.reviewer_participant_id = a.reviewed_participant_id
                    and b.reviewed_participant_id = a.reviewer_participant_id
                where a.reviewer_participant_id < a.reviewed_participant_id
                group by a.reviewer_participant_id, a.reviewed_participant_id
                """);
    }

    List<Map<String, Object>> grouped(String sql) {
        return jdbcTemplate.queryForList(sql);
    }

    List<ReviewAbuseClusterResponse> clusters(String status) {
        if (status == null || status.isBlank()) {
            return jdbcTemplate.query("select * from review_abuse_clusters order by created_at desc", this::clusterRow);
        }
        return jdbcTemplate.query("select * from review_abuse_clusters where status = ? order by created_at desc", this::clusterRow, status);
    }

    ReviewAbuseClusterResponse cluster(UUID clusterId) {
        return jdbcTemplate.queryForObject("select * from review_abuse_clusters where id = ?", this::clusterRow, clusterId);
    }

    void markSuppressed(UUID clusterId) {
        jdbcTemplate.update("update review_abuse_clusters set status = 'SUPPRESSED' where id = ?", clusterId);
    }

    int suppressReviews(UUID clusterId, List<UUID> reviewIds, String actor, String reason) {
        int updated = 0;
        for (UUID reviewId : reviewIds) {
            Integer previous = jdbcTemplate.queryForObject("select confidence_weight from marketplace_reviews where id = ?",
                    Integer.class, reviewId);
            int oldWeight = previous == null ? 0 : previous;
            jdbcTemplate.update("update marketplace_reviews set status = 'SUPPRESSED', confidence_weight = 0, suppression_reason = ? where id = ?",
                    reason, reviewId);
            jdbcTemplate.update("""
                    insert into review_suppression_actions (
                        id, review_id, abuse_cluster_id, suppression_type, previous_weight, new_weight, actor, reason
                    ) values (?, ?, ?, 'WEIGHT_ZEROED', ?, 0, ?, ?)
                    """, UUID.randomUUID(), reviewId, clusterId, oldWeight, actor, reason);
            updated++;
        }
        markSuppressed(clusterId);
        return updated;
    }

    List<ReviewAbuseClusterResponse> clustersForParticipant(UUID participantId) {
        return jdbcTemplate.query("""
                select * from review_abuse_clusters
                where member_participant_ids_json ? ?
                order by created_at desc
                """, this::clusterRow, participantId.toString());
    }

    private ReviewGraphEdgeResponse edgeRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewGraphEdgeResponse(rs.getObject("id", UUID.class), rs.getObject("review_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("reviewer_participant_id", UUID.class),
                rs.getObject("reviewed_participant_id", UUID.class), rs.getInt("rating"),
                rs.getLong("transaction_value_cents"), rs.getString("normalized_text_hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ReviewAbuseClusterResponse clusterRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewAbuseClusterResponse(rs.getObject("id", UUID.class), rs.getString("cluster_type"),
                rs.getString("severity"), rs.getString("status"), rs.getString("policy_version"),
                rs.getString("summary"), readStrings(rs.getString("signals_json")),
                readStrings(rs.getString("member_participant_ids_json")).stream().map(UUID::fromString).toList(),
                readStrings(rs.getString("review_ids_json")).stream().map(UUID::fromString).toList(),
                rs.getTimestamp("created_at").toInstant());
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    private List<String> readStrings(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }
}
