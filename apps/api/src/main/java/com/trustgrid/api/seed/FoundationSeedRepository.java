package com.trustgrid.api.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                "select id from participants where profile_slug = ?",
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                profileSlug
        );
        return ids.stream().findFirst();
    }

    UUID createParticipant(SeedParticipant participant) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participants (
                    id, display_name, profile_slug, account_status, verification_status, trust_tier, risk_level
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                participant.displayName(),
                participant.profileSlug(),
                participant.accountStatus(),
                participant.verificationStatus(),
                participant.trustTier(),
                participant.riskLevel()
        );
        jdbcTemplate.update("insert into participant_profiles (participant_id) values (?)", id);
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
        jdbcTemplate.update("""
                insert into trust_profiles (
                    participant_id, trust_tier, risk_level, trust_score, trust_confidence,
                    max_transaction_value_cents, restriction_flags_json, signals_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), '{}'::jsonb)
                """,
                participantId,
                participant.trustTier(),
                participant.riskLevel(),
                participant.trustScore().multiply(BigDecimal.TEN).setScale(0, RoundingMode.HALF_UP).intValue(),
                confidenceValue(participant.trustConfidence()),
                participant.maxTransactionValueCents(),
                restrictionFlags(participant)
        );
        return participantId;
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
                    id, participant_id, capability, status, granted_by, grant_reason, restricted_by, restrict_reason
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                participantId,
                capability.capability(),
                capability.status(),
                "trust-grid-api",
                "foundation seed",
                "RESTRICTED".equals(capability.status()) ? "trust-grid-api" : null,
                "RESTRICTED".equals(capability.status()) ? "foundation seed restriction" : null
        );
        return id;
    }

    void createEvent(String aggregateType, UUID aggregateId, UUID participantId, String eventType, String reason) {
        jdbcTemplate.update("""
                insert into marketplace_events (
                    id, aggregate_type, aggregate_id, participant_id, event_key, event_type, payload_json
                ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))
                """,
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                participantId,
                eventType + ":" + aggregateId + ":" + UUID.randomUUID(),
                eventType,
                "{\"reason\":\"" + reason + "\"}"
        );
    }

    private int confidenceValue(String confidence) {
        return switch (confidence) {
            case "HIGH" -> 100;
            case "MEDIUM" -> 50;
            default -> 0;
        };
    }

    private String restrictionFlags(SeedParticipant participant) {
        return """
                {"listingBlocked":%s,"acceptingBlocked":%s,"requiresManualReview":%s,"requiresVerification":%s}
                """.formatted(
                participant.listingBlocked(),
                participant.acceptingBlocked(),
                participant.requiresManualReview(),
                participant.requiresVerification()
        ).trim();
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
