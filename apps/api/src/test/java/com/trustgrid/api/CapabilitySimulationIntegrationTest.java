package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapabilitySimulationIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void simulationAnswersCanActNowWithReasonsNextStepsAndReadOnlyState() {
        createCapabilityPolicy("ACCEPT_TRANSACTION", Map.of(
                "requiredVerificationStatus", "VERIFIED",
                "maxValueCents", 10000
        ));
        UUID provider = createCapableParticipant("cap-sim-" + suffix(), "Capability Sim", "OFFER_SERVICES");
        int capabilitiesBefore = countRows("select count(*) from participant_capabilities where participant_id = ?", provider);

        Map<?, ?> denied = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 25000L);
        assertThat(denied.get("decision")).isEqualTo("REQUIRE_VERIFICATION");
        assertThat(denied.toString()).contains("VERIFICATION_REQUIRED", "VALUE_ABOVE_LIMIT", "complete the required verification workflow");

        Map<?, ?> repeated = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 25000L);
        assertThat(repeated.get("decision")).isEqualTo(denied.get("decision"));
        assertThat(repeated.get("denyReasons")).isEqualTo(denied.get("denyReasons"));
        assertThat(countRows("select count(*) from participant_capabilities where participant_id = ?", provider))
                .isEqualTo(capabilitiesBefore);

        verifyParticipant(provider);
        Map<?, ?> stillDenied = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 25000L);
        assertThat(stillDenied.toString()).contains("VALUE_ABOVE_LIMIT", "reduce the action value");
    }
}
