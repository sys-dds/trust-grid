package com.trustgrid.api.participant;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/participants")
public class ParticipantController {

    private final ParticipantService participantService;

    public ParticipantController(ParticipantService participantService) {
        this.participantService = participantService;
    }

    @PostMapping
    public ParticipantResponse create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateParticipantRequest request
    ) {
        return participantService.create(idempotencyKey, request);
    }

    @GetMapping("/{participantId}")
    public ParticipantResponse get(@PathVariable UUID participantId) {
        return participantService.get(participantId);
    }

    @GetMapping("/by-slug/{profileSlug}")
    public ParticipantResponse getBySlug(@PathVariable String profileSlug) {
        return participantService.getBySlug(profileSlug);
    }

    @GetMapping
    public ParticipantSearchResponse search(
            @RequestParam(required = false) String profileSlug,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) ParticipantAccountStatus accountStatus,
            @RequestParam(required = false) VerificationStatus verificationStatus,
            @RequestParam(required = false) TrustTier trustTier,
            @RequestParam(required = false) Capability capability,
            @RequestParam(required = false) Boolean restricted,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return participantService.search(null, profileSlug, displayName, accountStatus, verificationStatus, trustTier, capability, restricted, limit, offset);
    }

    @PatchMapping("/{participantId}/profile")
    public ParticipantResponse updateProfile(
            @PathVariable UUID participantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UpdateParticipantProfileRequest request
    ) {
        return participantService.updateProfile(participantId, idempotencyKey, request);
    }

    @GetMapping("/{participantId}/capabilities")
    public List<CapabilityResponse> capabilities(@PathVariable UUID participantId) {
        return participantService.capabilities(participantId);
    }

    @PostMapping("/{participantId}/capabilities")
    public CapabilityResponse grantCapability(
            @PathVariable UUID participantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CapabilityMutationRequest request
    ) {
        return participantService.grantCapability(participantId, idempotencyKey, request);
    }

    @PostMapping("/{participantId}/capabilities/{capability}/revoke")
    public CapabilityResponse revokeCapability(
            @PathVariable UUID participantId,
            @PathVariable Capability capability,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ParticipantActionRequest request
    ) {
        return participantService.revokeCapability(participantId, capability, idempotencyKey, request);
    }

    @PostMapping("/{participantId}/capabilities/{capability}/restrict")
    public CapabilityResponse restrictCapability(
            @PathVariable UUID participantId,
            @PathVariable Capability capability,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ParticipantActionRequest request
    ) {
        return participantService.restrictCapability(participantId, capability, idempotencyKey, request);
    }

    @PostMapping("/{participantId}/verification")
    public ParticipantResponse updateVerification(
            @PathVariable UUID participantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VerificationUpdateRequest request
    ) {
        return participantService.updateVerification(participantId, idempotencyKey, request);
    }

    @PostMapping("/{participantId}/account-status")
    public ParticipantResponse updateAccountStatus(
            @PathVariable UUID participantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AccountStatusUpdateRequest request
    ) {
        return participantService.updateAccountStatus(participantId, idempotencyKey, request);
    }

    @PostMapping("/{participantId}/restrictions")
    public RestrictionResponse applyRestriction(
            @PathVariable UUID participantId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ApplyRestrictionRequest request
    ) {
        return participantService.applyRestriction(participantId, idempotencyKey, request);
    }

    @PostMapping("/{participantId}/restrictions/{restrictionId}/remove")
    public RestrictionResponse removeRestriction(
            @PathVariable UUID participantId,
            @PathVariable UUID restrictionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ParticipantActionRequest request
    ) {
        return participantService.removeRestriction(participantId, restrictionId, idempotencyKey, request);
    }

    @GetMapping("/{participantId}/restrictions")
    public List<RestrictionResponse> restrictions(@PathVariable UUID participantId) {
        return participantService.restrictions(participantId);
    }

    @GetMapping("/{participantId}/trust-summary")
    public ParticipantTrustSummaryResponse trustSummary(@PathVariable UUID participantId) {
        return participantService.trustSummary(participantId);
    }

    @GetMapping("/{participantId}/timeline")
    public TimelineResponse timeline(
            @PathVariable UUID participantId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return participantService.timeline(participantId, eventType, from, to, limit, offset);
    }
}
