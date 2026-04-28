package com.trustgrid.api.review;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<TransactionReviewView> transaction(UUID transactionId) {
        return jdbcTemplate.query("""
                select id, requester_participant_id, provider_participant_id, status, value_amount_cents
                from marketplace_transactions where id = ?
                """, (rs, rowNum) -> new TransactionReviewView(
                rs.getObject("id", UUID.class),
                rs.getObject("requester_participant_id", UUID.class),
                rs.getObject("provider_participant_id", UUID.class),
                rs.getString("status"),
                rs.getLong("value_amount_cents")
        ), transactionId).stream().findFirst();
    }

    boolean hasUnresolvedDispute(UUID transactionId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_disputes
                where transaction_id = ? and status in (
                    'OPEN', 'AWAITING_BUYER_EVIDENCE', 'AWAITING_SELLER_EVIDENCE',
                    'AWAITING_PROVIDER_EVIDENCE', 'UNDER_REVIEW', 'ESCALATED'
                )
                """, Integer.class, transactionId);
        return count != null && count > 0;
    }

    boolean reviewExists(UUID transactionId, UUID reviewerId, UUID reviewedId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_reviews
                where transaction_id = ? and reviewer_participant_id = ? and reviewed_participant_id = ?
                """, Integer.class, transactionId, reviewerId, reviewedId);
        return count != null && count > 0;
    }

    String participantTier(UUID participantId) {
        return jdbcTemplate.queryForObject("""
                select trust_tier from participants where id = ?
                """, String.class, participantId);
    }

    int repeatPairCount(UUID a, UUID b) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from marketplace_transactions
                where status = 'COMPLETED'
                  and ((requester_participant_id = ? and provider_participant_id = ?)
                    or (requester_participant_id = ? and provider_participant_id = ?))
                """, Integer.class, a, b, b, a);
        return count == null ? 0 : count;
    }

    UUID insert(UUID transactionId, CreateReviewRequest request, int confidenceWeight) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_reviews (
                    id, transaction_id, reviewer_participant_id, reviewed_participant_id, status, overall_rating,
                    accuracy_rating, reliability_rating, communication_rating, punctuality_rating,
                    evidence_quality_rating, item_service_match_rating, review_text, confidence_weight
                ) values (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, transactionId, request.reviewerParticipantId(), request.reviewedParticipantId(),
                request.overallRating(), request.accuracyRating(), request.reliabilityRating(),
                request.communicationRating(), request.punctualityRating(), request.evidenceQualityRating(),
                request.itemServiceMatchRating(), request.reviewText(), confidenceWeight);
        return id;
    }

    Optional<ReviewResponse> find(UUID reviewId) {
        return jdbcTemplate.query("""
                select id, transaction_id, reviewer_participant_id, reviewed_participant_id, status, overall_rating,
                       accuracy_rating, reliability_rating, communication_rating, punctuality_rating,
                       evidence_quality_rating, item_service_match_rating, review_text, confidence_weight,
                       suppression_reason, created_at
                from marketplace_reviews where id = ?
                """, this::row, reviewId).stream().findFirst();
    }

    List<ReviewResponse> participantReviews(UUID participantId) {
        return jdbcTemplate.query("""
                select id, transaction_id, reviewer_participant_id, reviewed_participant_id, status, overall_rating,
                       accuracy_rating, reliability_rating, communication_rating, punctuality_rating,
                       evidence_quality_rating, item_service_match_rating, review_text, confidence_weight,
                       suppression_reason, created_at
                from marketplace_reviews
                where reviewed_participant_id = ? order by created_at desc
                """, this::row, participantId);
    }

    private ReviewResponse row(ResultSet rs, int rowNum) throws SQLException {
        return new ReviewResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("reviewer_participant_id", UUID.class),
                rs.getObject("reviewed_participant_id", UUID.class),
                rs.getString("status"),
                rs.getInt("overall_rating"),
                (Integer) rs.getObject("accuracy_rating"),
                (Integer) rs.getObject("reliability_rating"),
                (Integer) rs.getObject("communication_rating"),
                (Integer) rs.getObject("punctuality_rating"),
                (Integer) rs.getObject("evidence_quality_rating"),
                (Integer) rs.getObject("item_service_match_rating"),
                rs.getString("review_text"),
                rs.getInt("confidence_weight"),
                rs.getString("suppression_reason"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    public record TransactionReviewView(UUID transactionId, UUID requesterParticipantId, UUID providerParticipantId,
                                        String status, long valueAmountCents) {
    }
}
