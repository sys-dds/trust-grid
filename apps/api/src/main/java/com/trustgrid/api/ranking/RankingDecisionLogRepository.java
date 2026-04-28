package com.trustgrid.api.ranking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RankingDecisionLogRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RankingDecisionLogRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    List<RankingCandidate> candidates(String query, String listingType, String categoryCode, String locationMode,
                                      Long minPriceCents, Long maxPriceCents, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select d.listing_id, d.owner_participant_id, d.listing_type, d.category_code, d.title, d.location_mode,
                       coalesce(d.price_amount_cents, d.budget_amount_cents, 0) as amount_cents,
                       coalesce(tp.trust_score, 500) as trust_score,
                       coalesce(tp.trust_confidence, 0) as trust_confidence,
                       p.trust_tier, p.verification_status
                from listing_search_documents d
                join participants p on p.id = d.owner_participant_id
                left join trust_profiles tp on tp.participant_id = p.id
                where d.searchable = true
                  and d.status = 'LIVE'
                  and p.account_status not in ('SUSPENDED', 'CLOSED', 'RESTRICTED')
                  and not exists (
                      select 1 from participant_restrictions r
                      where r.participant_id = p.id
                        and r.status = 'ACTIVE'
                        and r.restriction_type = 'HIDDEN_FROM_MARKETPLACE_SEARCH'
                  )
                """);
        if (query != null && !query.isBlank()) {
            sql.append(" and (lower(d.title) like lower(?) or lower(d.description) like lower(?)) ");
            args.add("%" + query + "%");
            args.add("%" + query + "%");
        }
        if (listingType != null && !listingType.isBlank()) {
            sql.append(" and d.listing_type = ? ");
            args.add(listingType);
        }
        if (categoryCode != null && !categoryCode.isBlank()) {
            sql.append(" and d.category_code = ? ");
            args.add(categoryCode);
        }
        if (locationMode != null && !locationMode.isBlank()) {
            sql.append(" and d.location_mode = ? ");
            args.add(locationMode);
        }
        if (minPriceCents != null) {
            sql.append(" and coalesce(d.price_amount_cents, d.budget_amount_cents, 0) >= ? ");
            args.add(minPriceCents);
        }
        if (maxPriceCents != null) {
            sql.append(" and coalesce(d.price_amount_cents, d.budget_amount_cents, 0) <= ? ");
            args.add(maxPriceCents);
        }
        sql.append(" order by d.updated_at desc limit ? offset ? ");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::candidateRow, args.toArray());
    }

    UUID insertLog(String query, Map<String, Object> filters, RankingPolicyVersion policyVersion,
                   List<RankingCandidate> candidates, List<RankingListingResponse> results) {
        UUID id = UUID.randomUUID();
        List<String> candidateIds = candidates.stream().map(candidate -> candidate.listingId().toString()).toList();
        List<String> resultIds = results.stream().map(result -> result.listingId().toString()).toList();
        Map<String, Object> scores = new LinkedHashMap<>();
        Map<String, Object> reasons = new LinkedHashMap<>();
        for (RankingListingResponse result : results) {
            scores.put(result.listingId().toString(), result.score());
            reasons.put(result.listingId().toString(), result.reasons());
        }
        jdbcTemplate.update("""
                insert into ranking_decision_logs (
                    id, query_text, filters_json, policy_version, candidate_ids_json, result_ids_json,
                    scores_json, reasons_json, trust_risk_snapshot_json
                ) values (?, ?, cast(? as jsonb), ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                """, id, query, json(filters), policyVersion.name(), json(candidateIds), json(resultIds),
                json(scores), json(reasons), json(Map.of("candidateCount", candidates.size())));
        return id;
    }

    RankingReplayResponse replay(UUID rankingDecisionId) {
        return jdbcTemplate.queryForObject("""
                select id, policy_version, result_ids_json, reasons_json
                from ranking_decision_logs where id = ?
                """, (rs, rowNum) -> {
            List<UUID> ids = readStringList(rs.getString("result_ids_json")).stream().map(UUID::fromString).toList();
            return new RankingReplayResponse(
                    rs.getObject("id", UUID.class),
                    ids,
                    ids,
                    true,
                    RankingPolicyVersion.valueOf(rs.getString("policy_version")),
                    "replayed_from_stored_snapshot"
            );
        }, rankingDecisionId);
    }

    private RankingCandidate candidateRow(ResultSet rs, int rowNum) throws SQLException {
        return new RankingCandidate(
                rs.getObject("listing_id", UUID.class),
                rs.getObject("owner_participant_id", UUID.class),
                rs.getString("listing_type"),
                rs.getString("category_code"),
                rs.getString("title"),
                rs.getString("location_mode"),
                rs.getLong("amount_cents"),
                rs.getInt("trust_score"),
                rs.getInt("trust_confidence"),
                rs.getString("trust_tier"),
                rs.getString("verification_status")
        );
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception exception) {
            return List.of();
        }
    }

    public record RankingCandidate(UUID listingId, UUID ownerParticipantId, String listingType, String categoryCode,
                                   String title, String locationMode, long amountCents, int trustScore,
                                   int trustConfidence, String trustTier, String verificationStatus) {
    }
}
