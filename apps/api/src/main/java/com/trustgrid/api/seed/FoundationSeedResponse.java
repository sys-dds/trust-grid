package com.trustgrid.api.seed;

public record FoundationSeedResponse(
        int participantsCreated,
        int trustProfilesCreated,
        int capabilitiesCreated,
        int eventsCreated
) {
}
