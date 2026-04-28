package com.trustgrid.api.seed;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FoundationSeedRepository {

    private final JdbcTemplate jdbcTemplate;

    public FoundationSeedRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    Optional<UUID> findParticipantIdBySlug(String profileSlug) {
        List<UUID> ids = jdbcTemplate.query(
                "select id from marketplace_participants where profile_slug = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                profileSlug
        );
        return ids.stream().findFirst();
    }

    UUID createParticipant(SeedParticipant participant) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into marketplace_participants (
                    id, display_name, profile_slug, account_status, verification_status, profile_quality_score
                ) values (?, ?, ?, ?, ?, ?)
                """,
                id,
                participant.displayName(),
                participant.profileSlug(),
                participant.accountStatus(),
                participant.verificationStatus(),
                BigDecimal.ZERO
        );
        return id;
    }

    boolean trustProfileExists(UUID participantId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from trust_profiles where participant_id = ?",
                Integer.class,
                participantId
        );
        return count != null && count > 0;
    }

    UUID createTrustProfile(UUID participantId, SeedParticipant participant) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trust_profiles (
                    id, participant_id, trust_tier, risk_level, trust_score, trust_confidence,
                    max_transaction_value_cents, listing_blocked, accepting_blocked,
                    requires_manual_review, requires_verification
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                participantId,
                participant.trustTier(),
                participant.riskLevel(),
                participant.trustScore(),
                participant.trustConfidence(),
                participant.maxTransactionValueCents(),
                participant.listingBlocked(),
                participant.acceptingBlocked(),
                participant.requiresManualReview(),
                participant.requiresVerification()
        );
        return id;
    }

    boolean capabilityExists(UUID participantId, String capability) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from participant_capabilities where participant_id = ? and capability = ?",
                Integer.class,
                participantId,
                capability
        );
        return count != null && count > 0;
    }

    UUID createCapability(UUID participantId, SeedCapability capability) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_capabilities (
                    id, participant_id, capability, status, reason
                ) values (?, ?, ?, ?, ?)
                """,
                id,
                participantId,
                capability.capability(),
                capability.status(),
                "foundation seed"
        );
        return id;
    }

    void createEvent(String aggregateType, UUID aggregateId, String eventType, String reason) {
        jdbcTemplate.update("""
                insert into marketplace_events (
                    id, aggregate_type, aggregate_id, event_type, actor, reason
                ) values (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                "trust-grid-api",
                reason
        );
    }

    record SeedParticipant(
            String profileSlug,
            String displayName,
            String accountStatus,
            String verificationStatus,
            String trustTier,
            String riskLevel,
            BigDecimal trustScore,
            String trustConfidence,
            long maxTransactionValueCents,
            boolean listingBlocked,
            boolean acceptingBlocked,
            boolean requiresManualReview,
            boolean requiresVerification,
            List<SeedCapability> capabilities
    ) {
    }

    record SeedCapability(String capability, String status) {
    }
}
