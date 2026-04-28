package com.trustgrid.api.participant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/participants")
public class ParticipantAdminController {

    private final ParticipantService participantService;

    public ParticipantAdminController(ParticipantService participantService) {
        this.participantService = participantService;
    }

    @GetMapping
    public ParticipantSearchResponse search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String profileSlug,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) ParticipantAccountStatus accountStatus,
            @RequestParam(required = false) TrustTier trustTier,
            @RequestParam(required = false) VerificationStatus verificationStatus,
            @RequestParam(required = false) Capability capability,
            @RequestParam(required = false) Boolean restricted,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        return participantService.search(query, profileSlug, displayName, accountStatus, verificationStatus, trustTier, capability, restricted, limit, offset);
    }
}
