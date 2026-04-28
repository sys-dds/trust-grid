package com.trustgrid.api.seed;

import com.trustgrid.api.seed.FoundationSeedRepository.SeedCapability;
import com.trustgrid.api.seed.FoundationSeedRepository.SeedParticipant;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoundationSeedService {

    private static final UUID SYSTEM_AGGREGATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final FoundationSeedRepository repository;
    private final boolean endpointEnabled;

    public FoundationSeedService(
            FoundationSeedRepository repository,
            @Value("${trustgrid.seed.endpoint-enabled}") boolean endpointEnabled
    ) {
        this.repository = repository;
        this.endpointEnabled = endpointEnabled;
    }

    @Transactional
    public FoundationSeedResponse seed() {
        if (!endpointEnabled) {
            throw new MarketplaceActionForbiddenException("Foundation seed endpoint is disabled");
        }

        int participantsCreated = 0;
        int trustProfilesCreated = 0;
        int capabilitiesCreated = 0;
        int eventsCreated = 0;

        for (SeedParticipant participant : participants()) {
            var existingParticipantId = repository.findParticipantIdBySlug(participant.profileSlug());
            boolean createdParticipant = existingParticipantId.isEmpty();
            UUID participantId = existingParticipantId.orElseGet(() -> repository.createParticipant(participant));
            if (createdParticipant) {
                participantsCreated++;
                repository.createEvent("PARTICIPANT", participantId, "PARTICIPANT_CREATED", "foundation seed participant created");
                eventsCreated++;
            }

            if (!repository.trustProfileExists(participantId)) {
                UUID trustProfileId = repository.createTrustProfile(participantId, participant);
                trustProfilesCreated++;
                repository.createEvent("TRUST_PROFILE", trustProfileId, "TRUST_PROFILE_CREATED", "foundation seed trust profile created");
                eventsCreated++;
            }

            for (SeedCapability capability : participant.capabilities()) {
                if (!repository.capabilityExists(participantId, capability.capability())) {
                    UUID capabilityId = repository.createCapability(participantId, capability);
                    capabilitiesCreated++;
                    String eventType = "RESTRICTED".equals(capability.status())
                            ? "CAPABILITY_RESTRICTED"
                            : "CAPABILITY_GRANTED";
                    repository.createEvent("CAPABILITY", capabilityId, eventType, "foundation seed capability created");
                    eventsCreated++;
                }
            }
        }

        repository.createEvent("SYSTEM", SYSTEM_AGGREGATE_ID, "FOUNDATION_SEED_CREATED", "foundation seed request completed");
        eventsCreated++;

        return new FoundationSeedResponse(participantsCreated, trustProfilesCreated, capabilitiesCreated, eventsCreated);
    }

    private List<SeedParticipant> participants() {
        return List.of(
                new SeedParticipant(
                        "new-buyer",
                        "New Buyer",
                        "ACTIVE",
                        "BASIC",
                        "NEW",
                        "MEDIUM",
                        new BigDecimal("15.00"),
                        "LOW",
                        3000,
                        false,
                        false,
                        false,
                        false,
                        List.of(new SeedCapability("BUY", "ACTIVE"))
                ),
                new SeedParticipant(
                        "trusted-service-provider",
                        "Trusted Service Provider",
                        "ACTIVE",
                        "VERIFIED",
                        "TRUSTED",
                        "LOW",
                        new BigDecimal("82.00"),
                        "HIGH",
                        50000,
                        false,
                        false,
                        false,
                        false,
                        List.of(
                                new SeedCapability("OFFER_SERVICES", "ACTIVE"),
                                new SeedCapability("BUY", "ACTIVE")
                        )
                ),
                new SeedParticipant(
                        "new-shopper",
                        "New Shopper",
                        "ACTIVE",
                        "BASIC",
                        "NEW",
                        "MEDIUM",
                        new BigDecimal("20.00"),
                        "LOW",
                        3000,
                        false,
                        false,
                        false,
                        false,
                        List.of(
                                new SeedCapability("ACCEPT_SHOPPING_REQUESTS", "ACTIVE"),
                                new SeedCapability("ACCEPT_ERRANDS", "ACTIVE")
                        )
                ),
                new SeedParticipant(
                        "restricted-suspicious-participant",
                        "Restricted Suspicious Participant",
                        "RESTRICTED",
                        "UNVERIFIED",
                        "RESTRICTED",
                        "HIGH",
                        new BigDecimal("5.00"),
                        "LOW",
                        0,
                        true,
                        true,
                        true,
                        true,
                        List.of(new SeedCapability("BUY", "RESTRICTED"))
                )
        );
    }
}
