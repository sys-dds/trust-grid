package com.trustgrid.api.reputation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReputationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReputationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    Optional<ParticipantReputationView> participant(UUID participantId) {
        return jdbcTemplate.query("""
                select p.id, p.account_status, p.verification_status, coalesce(tp.trust_score, 500) as trust_score,
                       coalesce(tp.trust_confidence, 0) as trust_confidence, coalesce(tp.trust_tier, p.trust_tier) as trust_tier,
                       coalesce(tp.risk_level, p.risk_level) as risk_level
                from participants p
                left join trust_profiles tp on tp.participant_id = p.id
                where p.id = ?
                """, (rs, rowNum) -> new ParticipantReputationView(
                rs.getObject("id", UUID.class),
                rs.getString("account_status"),
                rs.getString("verification_status"),
                rs.getInt("trust_score"),
                rs.getInt("trust_confidence"),
                rs.getString("trust_tier"),
                rs.getString("risk_level")
        ), participantId).stream().findFirst();
    }

    ReviewStats reviewStats(UUID participantId) {
        return jdbcTemplate.queryForObject("""
                select count(*) as review_count,
                       coalesce(avg(overall_rating), 0) as average_rating,
                       coalesce(avg(confidence_weight), 0) as average_weight
                from marketplace_reviews
                where reviewed_participant_id = ? and status = 'ACTIVE'
                """, (rs, rowNum) -> new ReviewStats(rs.getInt("review_count"), rs.getDouble("average_rating"), rs.getInt("average_weight")), participantId);
    }

    int completedTransactions(UUID participantId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_transactions
                where status = 'COMPLETED' and (requester_participant_id = ? or provider_participant_id = ?)
                """, Integer.class, participantId, participantId);
        return count == null ? 0 : count;
    }

    int negativeTransactionSignals(UUID participantId, String status) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_transactions
                where status = ? and (requester_participant_id = ? or provider_participant_id = ?)
                """, Integer.class, status, participantId, participantId);
        return count == null ? 0 : count;
    }

    int disputeCount(UUID participantId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from marketplace_disputes d
                join marketplace_transactions t on t.id = d.transaction_id
                where t.requester_participant_id = ? or t.provider_participant_id = ?
                """, Integer.class, participantId, participantId);
        return count == null ? 0 : count;
    }

    int profileQuality(UUID participantId) {
        Integer score = jdbcTemplate.queryForObject("""
                select coalesce(profile_completeness_score, 0) from participant_profiles where participant_id = ?
                """, Integer.class, participantId);
        return score == null ? 0 : score;
    }

    ReputationSnapshotResponse insertSnapshot(UUID participantId, int score, int confidence, String tier, String riskLevel,
                                              int reviewScore, int completionRate, int cancellationPenalty, int noShowPenalty,
                                              int disputePenalty, int evidenceReliability, int profileQuality,
                                              List<String> strengths, List<String> penalties, Map<String, Object> signals) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into reputation_snapshots (
                    id, participant_id, trust_score, trust_confidence, trust_tier, risk_level, review_score,
                    completion_rate, cancellation_penalty, no_show_penalty, dispute_penalty, evidence_reliability,
                    profile_quality, strengths_json, penalties_json, contributing_signals_json, policy_version
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'reputation_v1')
                """, id, participantId, score, confidence, tier, riskLevel, reviewScore, completionRate,
                cancellationPenalty, noShowPenalty, disputePenalty, evidenceReliability, profileQuality,
                json(strengths), json(penalties), json(signals));
        jdbcTemplate.update("""
                update trust_profiles
                set trust_score = ?, trust_confidence = ?, trust_tier = ?, risk_level = ?, updated_at = now()
                where participant_id = ?
                """, score, confidence, tier, riskLevel, participantId);
        jdbcTemplate.update("""
                update participants
                set trust_tier = ?, risk_level = ?, updated_at = now()
                where id = ?
                """, tier, riskLevel, participantId);
        return new ReputationSnapshotResponse(participantId, score, confidence, tier, riskLevel, strengths, penalties,
                List.of("reputation_v1"), Map.of("reviewScore", reviewScore, "averageWeight", signals.get("averageWeight")), Instant.now());
    }

    void insertRecalculation(UUID participantId, Integer previousScore, int newScore, String previousTier, String newTier,
                             String reason, Map<String, Object> signals) {
        jdbcTemplate.update("""
                insert into reputation_recalculation_events (
                    id, participant_id, previous_score, new_score, previous_tier, new_tier, policy_version,
                    reason, contributing_signals_json
                ) values (?, ?, ?, ?, ?, ?, 'reputation_v1', ?, cast(? as jsonb))
                """, UUID.randomUUID(), participantId, previousScore, newScore, previousTier, newTier, reason, json(signals));
    }

    Optional<ReputationSnapshotResponse> latestSnapshot(UUID participantId) {
        return jdbcTemplate.query("""
                select participant_id, trust_score, trust_confidence, trust_tier, risk_level, strengths_json,
                       penalties_json, contributing_signals_json, created_at
                from reputation_snapshots
                where participant_id = ?
                order by created_at desc
                limit 1
                """, this::snapshotRow, participantId).stream().findFirst();
    }

    ReputationSnapshotResponse trustProfileReadModel(UUID participantId) {
        ParticipantReputationView participant = participant(participantId)
                .orElseThrow(() -> new com.trustgrid.api.shared.NotFoundException("Participant not found"));
        return new ReputationSnapshotResponse(participantId, participant.trustScore(), participant.trustConfidence(),
                participant.trustTier(), participant.riskLevel(), List.of(), List.of(), List.of("trust_profile_read_model"),
                Map.of("source", "trust_profile", "averageWeight", 0), null);
    }

    int snapshotCount(UUID participantId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from reputation_snapshots where participant_id = ?",
                Integer.class, participantId);
        return count == null ? 0 : count;
    }

    int recalculationEventCount(UUID participantId) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from reputation_recalculation_events where participant_id = ?",
                Integer.class, participantId);
        return count == null ? 0 : count;
    }

    private ReputationSnapshotResponse snapshotRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReputationSnapshotResponse(
                rs.getObject("participant_id", UUID.class),
                rs.getInt("trust_score"),
                rs.getInt("trust_confidence"),
                rs.getString("trust_tier"),
                rs.getString("risk_level"),
                readStringList(rs.getString("strengths_json")),
                readStringList(rs.getString("penalties_json")),
                List.of("reputation_v1"),
                readMap(rs.getString("contributing_signals_json")),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
            });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    public record ParticipantReputationView(UUID participantId, String accountStatus, String verificationStatus,
                                            int trustScore, int trustConfidence, String trustTier, String riskLevel) {
    }

    public record ReviewStats(int reviewCount, double averageRating, int averageWeight) {
    }
}
