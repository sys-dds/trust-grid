package com.trustgrid.api.participant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ParticipantRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ParticipantRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    Optional<ParticipantResponse> findParticipant(UUID participantId) {
        return jdbcTemplate.query("""
                select p.id, p.profile_slug, p.display_name, p.account_status, p.verification_status,
                       p.trust_tier, p.risk_level, p.created_at, p.updated_at,
                       pp.bio, pp.location_summary, pp.capability_description, pp.profile_photo_object_key,
                       pp.profile_completeness_score
                from participants p
                join participant_profiles pp on pp.participant_id = p.id
                where p.id = ?
                """, this::participantRow, participantId).stream().findFirst();
    }

    Optional<ParticipantResponse> findParticipantBySlug(String profileSlug) {
        return jdbcTemplate.query("""
                select p.id, p.profile_slug, p.display_name, p.account_status, p.verification_status,
                       p.trust_tier, p.risk_level, p.created_at, p.updated_at,
                       pp.bio, pp.location_summary, pp.capability_description, pp.profile_photo_object_key,
                       pp.profile_completeness_score
                from participants p
                join participant_profiles pp on pp.participant_id = p.id
                where p.profile_slug = ?
                """, this::participantRow, profileSlug).stream().findFirst();
    }

    List<ParticipantResponse> searchParticipants(
            String query,
            String profileSlug,
            String displayName,
            ParticipantAccountStatus accountStatus,
            VerificationStatus verificationStatus,
            TrustTier trustTier,
            Capability capability,
            Boolean restricted,
            int limit,
            int offset
    ) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select distinct p.id, p.profile_slug, p.display_name, p.account_status, p.verification_status,
                       p.trust_tier, p.risk_level, p.created_at, p.updated_at,
                       pp.bio, pp.location_summary, pp.capability_description, pp.profile_photo_object_key,
                       pp.profile_completeness_score
                from participants p
                join participant_profiles pp on pp.participant_id = p.id
                """);
        if (capability != null) {
            sql.append(" join participant_capabilities pc on pc.participant_id = p.id and pc.status = 'ACTIVE' ");
        }
        if (Boolean.TRUE.equals(restricted)) {
            sql.append(" join participant_restrictions pr on pr.participant_id = p.id and pr.status = 'ACTIVE' ");
        }
        sql.append(" where 1=1 ");
        if (query != null && !query.isBlank()) {
            sql.append(" and (lower(p.profile_slug) like ? or lower(p.display_name) like ?) ");
            args.add("%" + query.toLowerCase() + "%");
            args.add("%" + query.toLowerCase() + "%");
        }
        if (profileSlug != null && !profileSlug.isBlank()) {
            sql.append(" and lower(p.profile_slug) like ? ");
            args.add("%" + profileSlug.toLowerCase() + "%");
        }
        if (displayName != null && !displayName.isBlank()) {
            sql.append(" and lower(p.display_name) like ? ");
            args.add("%" + displayName.toLowerCase() + "%");
        }
        if (accountStatus != null) {
            sql.append(" and p.account_status = ? ");
            args.add(accountStatus.name());
        }
        if (verificationStatus != null) {
            sql.append(" and p.verification_status = ? ");
            args.add(verificationStatus.name());
        }
        if (trustTier != null) {
            sql.append(" and p.trust_tier = ? ");
            args.add(trustTier.name());
        }
        if (capability != null) {
            sql.append(" and pc.capability = ? ");
            args.add(capability.name());
        }
        if (Boolean.FALSE.equals(restricted)) {
            sql.append("""
                     and not exists (
                        select 1 from participant_restrictions rx
                        where rx.participant_id = p.id and rx.status = 'ACTIVE'
                     )
                    """);
        }
        sql.append(" order by p.created_at desc limit ? offset ? ");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::participantRow, args.toArray());
    }

    UUID insertParticipant(CreateParticipantRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participants (
                    id, profile_slug, display_name, account_status, verification_status, trust_tier, risk_level
                ) values (?, ?, ?, 'ACTIVE', 'UNVERIFIED', 'NEW', 'LOW')
                """, id, request.profileSlug(), request.displayName());
        jdbcTemplate.update("insert into participant_profiles (participant_id) values (?)", id);
        jdbcTemplate.update("insert into trust_profiles (participant_id) values (?)", id);
        return id;
    }

    void updateProfile(UUID participantId, UpdateParticipantProfileRequest request, int score) {
        jdbcTemplate.update("""
                update participants
                set display_name = ?, updated_at = now()
                where id = ?
                """, request.displayName(), participantId);
        jdbcTemplate.update("""
                update participant_profiles
                set bio = ?, location_summary = ?, capability_description = ?, profile_photo_object_key = ?,
                    profile_completeness_score = ?, updated_at = now()
                where participant_id = ?
                """,
                request.bio(),
                request.locationSummary(),
                request.capabilityDescription(),
                request.profilePhotoObjectKey(),
                score,
                participantId
        );
    }

    Optional<CapabilityResponse> findCapability(UUID participantId, Capability capability) {
        return jdbcTemplate.query("""
                select id, participant_id, capability, status, created_at, updated_at
                from participant_capabilities
                where participant_id = ? and capability = ?
                """, this::capabilityRow, participantId, capability.name()).stream().findFirst();
    }

    Optional<CapabilityResponse> findCapability(UUID capabilityId) {
        return jdbcTemplate.query("""
                select id, participant_id, capability, status, created_at, updated_at
                from participant_capabilities
                where id = ?
                """, this::capabilityRow, capabilityId).stream().findFirst();
    }

    List<CapabilityResponse> capabilities(UUID participantId) {
        return jdbcTemplate.query("""
                select id, participant_id, capability, status, created_at, updated_at
                from participant_capabilities
                where participant_id = ?
                order by capability
                """, this::capabilityRow, participantId);
    }

    UUID insertCapability(UUID participantId, CapabilityMutationRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_capabilities (
                    id, participant_id, capability, status, granted_by, grant_reason
                ) values (?, ?, ?, 'ACTIVE', ?, ?)
                """, id, participantId, request.capability().name(), request.actor(), request.reason());
        return id;
    }

    UUID insertRestrictedCapability(UUID participantId, Capability capability, String actor, String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_capabilities (
                    id, participant_id, capability, status, restricted_by, restrict_reason
                ) values (?, ?, ?, 'RESTRICTED', ?, ?)
                """, id, participantId, capability.name(), actor, reason);
        return id;
    }

    void reactivateCapability(UUID participantId, Capability capability, String actor, String reason) {
        jdbcTemplate.update("""
                update participant_capabilities
                set status = 'ACTIVE', granted_by = ?, grant_reason = ?, updated_at = now()
                where participant_id = ? and capability = ?
                """, actor, reason, participantId, capability.name());
    }

    void setCapabilityStatus(UUID participantId, Capability capability, CapabilityStatus status, String actor, String reason) {
        String actorColumn = status == CapabilityStatus.REVOKED ? "revoked_by" : "restricted_by";
        String reasonColumn = status == CapabilityStatus.REVOKED ? "revoke_reason" : "restrict_reason";
        jdbcTemplate.update("""
                update participant_capabilities
                set status = ?, %s = ?, %s = ?, updated_at = now()
                where participant_id = ? and capability = ?
                """.formatted(actorColumn, reasonColumn), status.name(), actor, reason, participantId, capability.name());
    }

    void updateVerification(UUID participantId, VerificationStatus oldStatus, VerificationUpdateRequest request) {
        jdbcTemplate.update("""
                insert into participant_verification_history (
                    id, participant_id, old_status, new_status, actor, reason
                ) values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), participantId, oldStatus.name(), request.newStatus().name(), request.actor(), request.reason());
        jdbcTemplate.update("""
                update participants
                set verification_status = ?, updated_at = now()
                where id = ?
                """, request.newStatus().name(), participantId);
        jdbcTemplate.update("""
                update trust_profiles
                set signals_json = cast(? as jsonb), updated_at = now()
                where participant_id = ?
                """, json(Map.of("verificationStatus", request.newStatus().name())), participantId);
    }

    void updateStatus(UUID participantId, ParticipantAccountStatus oldStatus, AccountStatusUpdateRequest request, TrustTier trustTier) {
        jdbcTemplate.update("""
                insert into participant_status_history (
                    id, participant_id, old_status, new_status, actor, reason
                ) values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), participantId, oldStatus.name(), request.newStatus().name(), request.actor(), request.reason());
        jdbcTemplate.update("""
                update participants
                set account_status = ?, trust_tier = ?, updated_at = now()
                where id = ?
                """, request.newStatus().name(), trustTier.name(), participantId);
        jdbcTemplate.update("""
                update trust_profiles
                set trust_tier = ?, updated_at = now()
                where participant_id = ?
                """, trustTier.name(), participantId);
    }

    UUID insertRestriction(UUID participantId, ApplyRestrictionRequest request) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into participant_restrictions (
                    id, participant_id, restriction_type, status, max_transaction_value_cents, actor, reason
                ) values (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                id,
                participantId,
                request.restrictionType().name(),
                request.maxTransactionValueCents(),
                request.actor(),
                request.reason()
        );
        refreshRestrictionFlags(participantId);
        return id;
    }

    void removeRestriction(UUID participantId, UUID restrictionId, ParticipantActionRequest request) {
        jdbcTemplate.update("""
                update participant_restrictions
                set status = 'REMOVED', removed_at = now(), removed_by = ?, remove_reason = ?
                where id = ? and participant_id = ? and status = 'ACTIVE'
                """, request.actor(), request.reason(), restrictionId, participantId);
        refreshRestrictionFlags(participantId);
    }

    Optional<RestrictionResponse> findRestriction(UUID participantId, UUID restrictionId) {
        return jdbcTemplate.query("""
                select id, participant_id, restriction_type, status, max_transaction_value_cents, created_at, removed_at
                from participant_restrictions
                where id = ? and participant_id = ?
                """, this::restrictionRow, restrictionId, participantId).stream().findFirst();
    }

    List<RestrictionResponse> restrictions(UUID participantId) {
        return jdbcTemplate.query("""
                select id, participant_id, restriction_type, status, max_transaction_value_cents, created_at, removed_at
                from participant_restrictions
                where participant_id = ?
                order by created_at desc
                """, this::restrictionRow, participantId);
    }

    Optional<TrustProfileData> trustProfile(UUID participantId) {
        return jdbcTemplate.query("""
                select participant_id, trust_score, trust_confidence, trust_tier, risk_level,
                       max_transaction_value_cents, restriction_flags_json, signals_json
                from trust_profiles
                where participant_id = ?
                """, (rs, rowNum) -> new TrustProfileData(
                rs.getObject("participant_id", UUID.class),
                rs.getInt("trust_score"),
                rs.getInt("trust_confidence"),
                TrustTier.valueOf(rs.getString("trust_tier")),
                RiskLevel.valueOf(rs.getString("risk_level")),
                rs.getLong("max_transaction_value_cents"),
                readMap(rs.getString("restriction_flags_json")),
                readMap(rs.getString("signals_json"))
        ), participantId).stream().findFirst();
    }

    List<TimelineEventResponse> timeline(UUID participantId, String eventType, Instant from, Instant to, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select id, aggregate_type, aggregate_id, participant_id, event_key, event_type,
                       event_status, payload_json, publish_attempts, published_at, last_error, created_at
                from marketplace_events
                where participant_id = ?
                """);
        args.add(participantId);
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" and event_type = ? ");
            args.add(eventType);
        }
        if (from != null) {
            sql.append(" and created_at >= ? ");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" and created_at <= ? ");
            args.add(Timestamp.from(to));
        }
        sql.append(" order by created_at desc limit ? offset ? ");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(), this::timelineRow, args.toArray());
    }

    void insertEvent(String aggregateType, UUID aggregateId, UUID participantId, String eventType, Map<String, Object> payload) {
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
                json(payload)
        );
    }

    Optional<IdempotencyRecord> findIdempotency(String scope, String key) {
        return jdbcTemplate.query("""
                select id, scope, idempotency_key, request_hash, resource_type, resource_id
                from idempotency_records
                where scope = ? and idempotency_key = ?
                """, (rs, rowNum) -> new IdempotencyRecord(
                rs.getObject("id", UUID.class),
                rs.getString("scope"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class)
        ), scope, key).stream().findFirst();
    }

    void insertIdempotency(String scope, String key, String requestHash, String resourceType, UUID resourceId) {
        jdbcTemplate.update("""
                insert into idempotency_records (
                    id, scope, idempotency_key, request_hash, resource_type, resource_id
                ) values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), scope, key, requestHash, resourceType, resourceId);
    }

    int countEvents(UUID participantId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from marketplace_events where participant_id = ? and event_type = ?",
                Integer.class,
                participantId,
                eventType
        );
        return count == null ? 0 : count;
    }

    private void refreshRestrictionFlags(UUID participantId) {
        List<RestrictionResponse> active = restrictions(participantId).stream()
                .filter(restriction -> "ACTIVE".equals(restriction.status()))
                .toList();
        Map<String, Object> flags = Map.of(
                "activeRestrictions", active.stream().map(restriction -> restriction.restrictionType().name()).toList()
        );
        long maxValue = active.stream()
                .filter(restriction -> restriction.restrictionType() == RestrictionType.MAX_TRANSACTION_VALUE)
                .filter(restriction -> restriction.maxTransactionValueCents() != null)
                .mapToLong(RestrictionResponse::maxTransactionValueCents)
                .min()
                .orElse(0);
        jdbcTemplate.update("""
                update trust_profiles
                set restriction_flags_json = cast(? as jsonb), max_transaction_value_cents = ?, updated_at = now()
                where participant_id = ?
                """, json(flags), maxValue, participantId);
    }

    private ParticipantResponse participantRow(ResultSet rs, int rowNum) throws SQLException {
        return new ParticipantResponse(
                rs.getObject("id", UUID.class),
                rs.getString("profile_slug"),
                rs.getString("display_name"),
                ParticipantAccountStatus.valueOf(rs.getString("account_status")),
                VerificationStatus.valueOf(rs.getString("verification_status")),
                TrustTier.valueOf(rs.getString("trust_tier")),
                RiskLevel.valueOf(rs.getString("risk_level")),
                rs.getString("bio"),
                rs.getString("location_summary"),
                rs.getString("capability_description"),
                rs.getString("profile_photo_object_key"),
                rs.getInt("profile_completeness_score"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private CapabilityResponse capabilityRow(ResultSet rs, int rowNum) throws SQLException {
        return new CapabilityResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("participant_id", UUID.class),
                Capability.valueOf(rs.getString("capability")),
                CapabilityStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private RestrictionResponse restrictionRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp removedAt = rs.getTimestamp("removed_at");
        return new RestrictionResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("participant_id", UUID.class),
                RestrictionType.valueOf(rs.getString("restriction_type")),
                rs.getString("status"),
                (Long) rs.getObject("max_transaction_value_cents"),
                rs.getTimestamp("created_at").toInstant(),
                removedAt == null ? null : removedAt.toInstant()
        );
    }

    private TimelineEventResponse timelineRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp publishedAt = rs.getTimestamp("published_at");
        return new TimelineEventResponse(
                rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getObject("participant_id", UUID.class),
                rs.getString("event_key"),
                rs.getString("event_type"),
                rs.getString("event_status"),
                readMap(rs.getString("payload_json")),
                rs.getInt("publish_attempts"),
                publishedAt == null ? null : publishedAt.toInstant(),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid JSON payload", exception);
        }
    }

    Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (Exception exception) {
            return Map.of();
        }
    }

    record TrustProfileData(
            UUID participantId,
            int trustScore,
            int trustConfidence,
            TrustTier trustTier,
            RiskLevel riskLevel,
            long maxTransactionValueCents,
            Map<String, Object> restrictionFlags,
            Map<String, Object> signals
    ) {
    }

    record IdempotencyRecord(
            UUID id,
            String scope,
            String idempotencyKey,
            String requestHash,
            String resourceType,
            UUID resourceId
    ) {
    }
}
