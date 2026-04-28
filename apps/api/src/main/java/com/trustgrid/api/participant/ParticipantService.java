package com.trustgrid.api.participant;

import com.trustgrid.api.participant.ParticipantRepository.IdempotencyRecord;
import com.trustgrid.api.participant.ParticipantRepository.TrustProfileData;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import com.trustgrid.api.shared.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParticipantService {

    private final ParticipantRepository repository;

    public ParticipantService(ParticipantRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public ParticipantResponse create(String idempotencyKey, CreateParticipantRequest request) {
        return idempotent("participant:create", idempotencyKey, request, "PARTICIPANT",
                id -> get(id),
                () -> {
                    repository.findParticipantBySlug(request.profileSlug()).ifPresent(existing -> {
                        throw new ConflictException("Profile slug already exists");
                    });
                    UUID participantId = repository.insertParticipant(request);
                    repository.insertEvent("PARTICIPANT", participantId, participantId, "PARTICIPANT_CREATED", Map.of(
                            "profileSlug", request.profileSlug(),
                            "displayName", request.displayName(),
                            "actor", request.createdBy(),
                            "reason", request.reason()
                    ));
                    repository.insertEvent("TRUST_PROFILE", participantId, participantId, "TRUST_PROFILE_INITIALIZED", Map.of(
                            "trustTier", TrustTier.NEW.name(),
                            "riskLevel", RiskLevel.LOW.name(),
                            "trustScore", 500
                    ));
                    return participantId;
                });
    }

    public ParticipantResponse get(UUID participantId) {
        return repository.findParticipant(participantId)
                .orElseThrow(() -> new NotFoundException("Participant not found"));
    }

    public ParticipantResponse getBySlug(String profileSlug) {
        return repository.findParticipantBySlug(profileSlug)
                .orElseThrow(() -> new NotFoundException("Participant not found"));
    }

    public ParticipantSearchResponse search(
            String query,
            String profileSlug,
            String displayName,
            ParticipantAccountStatus accountStatus,
            VerificationStatus verificationStatus,
            TrustTier trustTier,
            Capability capability,
            Boolean restricted,
            Integer limit,
            Integer offset
    ) {
        int resolvedLimit = clampLimit(limit);
        int resolvedOffset = Math.max(offset == null ? 0 : offset, 0);
        return new ParticipantSearchResponse(
                repository.searchParticipants(
                        query,
                        profileSlug,
                        displayName,
                        accountStatus,
                        verificationStatus,
                        trustTier,
                        capability,
                        restricted,
                        resolvedLimit,
                        resolvedOffset
                ),
                resolvedLimit,
                resolvedOffset
        );
    }

    @Transactional
    public ParticipantResponse updateProfile(UUID participantId, String idempotencyKey, UpdateParticipantProfileRequest request) {
        return idempotent("participant:profile:" + participantId, idempotencyKey, request, "PARTICIPANT",
                id -> get(id),
                () -> {
                    ParticipantResponse participant = get(participantId);
                    int score = completenessScore(participant.verificationStatus(), request);
                    repository.updateProfile(participantId, request, score);
                    repository.insertEvent("PARTICIPANT", participantId, participantId, "PROFILE_UPDATED", Map.of(
                            "displayName", request.displayName(),
                            "profileCompletenessScore", score,
                            "actor", request.updatedBy(),
                            "reason", request.reason()
                    ));
                    return participantId;
                });
    }

    public List<CapabilityResponse> capabilities(UUID participantId) {
        get(participantId);
        return repository.capabilities(participantId);
    }

    @Transactional
    public CapabilityResponse grantCapability(UUID participantId, String idempotencyKey, CapabilityMutationRequest request) {
        return idempotent("participant:capability:grant:" + participantId + ":" + request.capability(), idempotencyKey, request, "CAPABILITY",
                this::getCapability,
                () -> {
                    ParticipantResponse participant = get(participantId);
                    if (participant.accountStatus() == ParticipantAccountStatus.CLOSED) {
                        throw new MarketplaceActionForbiddenException("Closed participants cannot receive capabilities");
                    }
                    if (participant.accountStatus() == ParticipantAccountStatus.SUSPENDED) {
                        throw new MarketplaceActionForbiddenException("Suspended participants cannot use capabilities");
                    }
                    var existing = repository.findCapability(participantId, request.capability());
                    UUID capabilityId;
                    if (existing.isPresent()) {
                        if (existing.get().status() == CapabilityStatus.ACTIVE) {
                            throw new ConflictException("Capability is already active");
                        }
                        capabilityId = existing.get().id();
                        repository.reactivateCapability(participantId, request.capability(), request.actor(), request.reason());
                    } else {
                        capabilityId = repository.insertCapability(participantId, request);
                    }
                    repository.insertEvent("CAPABILITY", capabilityId, participantId, "CAPABILITY_GRANTED", Map.of(
                            "capability", request.capability().name(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    return capabilityId;
                });
    }

    @Transactional
    public CapabilityResponse revokeCapability(UUID participantId, Capability capability, String idempotencyKey, ParticipantActionRequest request) {
        return changeCapabilityStatus(participantId, capability, idempotencyKey, request, CapabilityStatus.REVOKED, "CAPABILITY_REVOKED");
    }

    @Transactional
    public CapabilityResponse restrictCapability(UUID participantId, Capability capability, String idempotencyKey, ParticipantActionRequest request) {
        return idempotent("participant:capability:restrict:" + participantId + ":" + capability, idempotencyKey, request, "CAPABILITY",
                this::getCapability,
                () -> {
                    get(participantId);
                    UUID capabilityId = repository.findCapability(participantId, capability)
                            .map(CapabilityResponse::id)
                            .orElseGet(() -> repository.insertRestrictedCapability(participantId, capability, request.actor(), request.reason()));
                    if (repository.findCapability(participantId, capability).orElseThrow().status() != CapabilityStatus.RESTRICTED) {
                        repository.setCapabilityStatus(participantId, capability, CapabilityStatus.RESTRICTED, request.actor(), request.reason());
                    }
                    repository.insertEvent("CAPABILITY", capabilityId, participantId, "CAPABILITY_RESTRICTED", Map.of(
                            "capability", capability.name(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    return capabilityId;
                });
    }

    @Transactional
    public ParticipantResponse updateVerification(UUID participantId, String idempotencyKey, VerificationUpdateRequest request) {
        return idempotent("participant:verification:" + participantId, idempotencyKey, request, "PARTICIPANT",
                id -> get(id),
                () -> {
                    ParticipantResponse participant = get(participantId);
                    repository.updateVerification(participantId, participant.verificationStatus(), request);
                    repository.insertEvent("PARTICIPANT", participantId, participantId, "VERIFICATION_STATUS_UPDATED", Map.of(
                            "oldStatus", participant.verificationStatus().name(),
                            "newStatus", request.newStatus().name(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    repository.insertEvent("TRUST_PROFILE", participantId, participantId, "TRUST_PROFILE_UPDATED", Map.of(
                            "signal", "verificationStatus",
                            "value", request.newStatus().name()
                    ));
                    return participantId;
                });
    }

    @Transactional
    public ParticipantResponse updateAccountStatus(UUID participantId, String idempotencyKey, AccountStatusUpdateRequest request) {
        return idempotent("participant:status:" + participantId, idempotencyKey, request, "PARTICIPANT",
                id -> get(id),
                () -> {
                    ParticipantResponse participant = get(participantId);
                    validateTransition(participant.accountStatus(), request.newStatus(), request.reason());
                    TrustTier newTier = trustTierForStatus(request.newStatus(), participant.trustTier());
                    repository.updateStatus(participantId, participant.accountStatus(), request, newTier);
                    repository.insertEvent("PARTICIPANT", participantId, participantId, "ACCOUNT_STATUS_UPDATED", Map.of(
                            "oldStatus", participant.accountStatus().name(),
                            "newStatus", request.newStatus().name(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    repository.insertEvent("TRUST_PROFILE", participantId, participantId, "TRUST_PROFILE_UPDATED", Map.of(
                            "trustTier", newTier.name()
                    ));
                    return participantId;
                });
    }

    @Transactional
    public RestrictionResponse applyRestriction(UUID participantId, String idempotencyKey, ApplyRestrictionRequest request) {
        return idempotent("participant:restriction:apply:" + participantId + ":" + request.restrictionType(), idempotencyKey, request, "RESTRICTION",
                id -> getRestriction(participantId, id),
                () -> {
                    get(participantId);
                    UUID restrictionId = repository.insertRestriction(participantId, request);
                    repository.insertEvent("RESTRICTION", restrictionId, participantId, "RESTRICTION_APPLIED", Map.of(
                            "restrictionType", request.restrictionType().name(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    repository.insertEvent("TRUST_PROFILE", participantId, participantId, "TRUST_PROFILE_UPDATED", Map.of(
                            "restrictionType", request.restrictionType().name(),
                            "status", "ACTIVE"
                    ));
                    return restrictionId;
                });
    }

    @Transactional
    public RestrictionResponse removeRestriction(UUID participantId, UUID restrictionId, String idempotencyKey, ParticipantActionRequest request) {
        return idempotent("participant:restriction:remove:" + participantId + ":" + restrictionId, idempotencyKey, request, "RESTRICTION",
                id -> getRestriction(participantId, id),
                () -> {
                    getRestriction(participantId, restrictionId);
                    repository.removeRestriction(participantId, restrictionId, request);
                    repository.insertEvent("RESTRICTION", restrictionId, participantId, "RESTRICTION_REMOVED", Map.of(
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    repository.insertEvent("TRUST_PROFILE", participantId, participantId, "TRUST_PROFILE_UPDATED", Map.of(
                            "restrictionId", restrictionId.toString(),
                            "status", "REMOVED"
                    ));
                    return restrictionId;
                });
    }

    public List<RestrictionResponse> restrictions(UUID participantId) {
        get(participantId);
        return repository.restrictions(participantId);
    }

    public ParticipantTrustSummaryResponse trustSummary(UUID participantId) {
        ParticipantResponse participant = get(participantId);
        TrustProfileData profile = repository.trustProfile(participantId)
                .orElseThrow(() -> new NotFoundException("Trust profile not found"));
        List<CapabilityResponse> capabilities = repository.capabilities(participantId);
        List<RestrictionResponse> restrictions = repository.restrictions(participantId).stream()
                .filter(restriction -> "ACTIVE".equals(restriction.status()))
                .toList();
        List<Capability> active = capabilities.stream()
                .filter(capability -> capability.status() == CapabilityStatus.ACTIVE)
                .map(CapabilityResponse::capability)
                .toList();
        List<Capability> revoked = capabilities.stream()
                .filter(capability -> capability.status() == CapabilityStatus.REVOKED)
                .map(CapabilityResponse::capability)
                .toList();
        List<Capability> restricted = capabilities.stream()
                .filter(capability -> capability.status() == CapabilityStatus.RESTRICTED)
                .map(CapabilityResponse::capability)
                .toList();
        List<RestrictionType> activeRestrictions = restrictions.stream()
                .map(RestrictionResponse::restrictionType)
                .toList();
        boolean canUseCapabilities = participant.accountStatus() != ParticipantAccountStatus.SUSPENDED
                && participant.accountStatus() != ParticipantAccountStatus.CLOSED;
        MarketplaceEligibilityResponse eligibility = new MarketplaceEligibilityResponse(
                canUseCapabilities && active.contains(Capability.BUY),
                canUseCapabilities && active.contains(Capability.SELL_ITEMS),
                canUseCapabilities && active.contains(Capability.OFFER_SERVICES),
                canUseCapabilities && active.contains(Capability.ACCEPT_ERRANDS),
                canUseCapabilities && active.contains(Capability.ACCEPT_SHOPPING_REQUESTS),
                !activeRestrictions.contains(RestrictionType.HIDDEN_FROM_MARKETPLACE_SEARCH),
                activeRestrictions.contains(RestrictionType.REQUIRES_MANUAL_REVIEW),
                activeRestrictions.contains(RestrictionType.REQUIRES_VERIFICATION)
        );
        return new ParticipantTrustSummaryResponse(
                participant.participantId(),
                participant.profileSlug(),
                participant.displayName(),
                participant.accountStatus(),
                participant.verificationStatus(),
                active,
                revoked,
                restricted,
                activeRestrictions,
                profile.trustTier(),
                profile.riskLevel(),
                profile.trustScore(),
                profile.trustConfidence(),
                profile.maxTransactionValueCents(),
                eligibility
        );
    }

    public TimelineResponse timeline(UUID participantId, String eventType, Instant from, Instant to, Integer limit, Integer offset) {
        get(participantId);
        int resolvedLimit = clampTimelineLimit(limit);
        int resolvedOffset = Math.max(offset == null ? 0 : offset, 0);
        return new TimelineResponse(repository.timeline(participantId, eventType, from, to, resolvedLimit, resolvedOffset), resolvedLimit, resolvedOffset);
    }

    private CapabilityResponse changeCapabilityStatus(
            UUID participantId,
            Capability capability,
            String idempotencyKey,
            ParticipantActionRequest request,
            CapabilityStatus status,
            String eventType
    ) {
        return idempotent("participant:capability:" + status.name().toLowerCase() + ":" + participantId + ":" + capability,
                idempotencyKey,
                request,
                "CAPABILITY",
                this::getCapability,
                () -> {
                    get(participantId);
                    CapabilityResponse existing = repository.findCapability(participantId, capability)
                            .orElseThrow(() -> new NotFoundException("Capability not found"));
                    repository.setCapabilityStatus(participantId, capability, status, request.actor(), request.reason());
                    repository.insertEvent("CAPABILITY", existing.id(), participantId, eventType, Map.of(
                            "capability", capability.name(),
                            "actor", request.actor(),
                            "reason", request.reason()
                    ));
                    return existing.id();
                });
    }

    private CapabilityResponse getCapability(UUID capabilityId) {
        return repository.findCapability(capabilityId)
                .orElseThrow(() -> new NotFoundException("Capability not found"));
    }

    private RestrictionResponse getRestriction(UUID participantId, UUID restrictionId) {
        return repository.findRestriction(participantId, restrictionId)
                .orElseThrow(() -> new NotFoundException("Restriction not found"));
    }

    private <T> T idempotent(
            String scope,
            String idempotencyKey,
            Object request,
            String resourceType,
            Function<UUID, T> existingResponse,
            Mutation mutation
    ) {
        requireIdempotencyKey(idempotencyKey);
        String requestHash = requestHash(scope, request);
        Optional<IdempotencyRecord> existing = repository.findIdempotency(scope, idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new ConflictException("Idempotency key was already used with a different request");
            }
            return existingResponse.apply(existing.get().resourceId());
        }
        UUID resourceId = mutation.apply();
        repository.insertIdempotency(scope, idempotencyKey, requestHash, resourceType, resourceId);
        return existingResponse.apply(resourceId);
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
    }

    private String requestHash(String scope, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((scope + ":" + request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Could not calculate idempotency request hash", exception);
        }
    }

    private int completenessScore(VerificationStatus verificationStatus, UpdateParticipantProfileRequest request) {
        int points = 0;
        points += hasText(request.displayName()) ? 20 : 0;
        points += hasText(request.bio()) ? 20 : 0;
        points += hasText(request.locationSummary()) ? 15 : 0;
        points += hasText(request.capabilityDescription()) ? 20 : 0;
        points += verificationStatus != VerificationStatus.UNVERIFIED && verificationStatus != VerificationStatus.REJECTED ? 15 : 0;
        points += hasText(request.profilePhotoObjectKey()) ? 10 : 0;
        return points;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, 100));
    }

    private int clampTimelineLimit(Integer limit) {
        if (limit == null) {
            return 50;
        }
        return Math.max(1, Math.min(limit, 100));
    }

    private void validateTransition(ParticipantAccountStatus oldStatus, ParticipantAccountStatus newStatus, String reason) {
        if (oldStatus == ParticipantAccountStatus.CLOSED && newStatus != ParticipantAccountStatus.CLOSED) {
            throw new ConflictException("Closed account status is terminal");
        }
        if (oldStatus == ParticipantAccountStatus.SUSPENDED
                && newStatus == ParticipantAccountStatus.ACTIVE
                && (reason == null || reason.isBlank())) {
            throw new ConflictException("Suspended participants require a reason to become active");
        }
    }

    private TrustTier trustTierForStatus(ParticipantAccountStatus status, TrustTier current) {
        return switch (status) {
            case ACTIVE -> current == TrustTier.LIMITED || current == TrustTier.RESTRICTED || current == TrustTier.SUSPENDED
                    ? TrustTier.NEW
                    : current;
            case LIMITED -> TrustTier.LIMITED;
            case RESTRICTED -> TrustTier.RESTRICTED;
            case SUSPENDED, CLOSED -> TrustTier.SUSPENDED;
        };
    }

    private interface Mutation {
        UUID apply();
    }
}
