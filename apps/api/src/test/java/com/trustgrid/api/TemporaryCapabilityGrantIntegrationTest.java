package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TemporaryCapabilityGrantIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void temporaryGrantIsScopedExpiresRevokesAndDoesNotBypassSuspension() {
        createCapabilityPolicy("ACCEPT_TRANSACTION", Map.of("maxValueCents", 1000));
        UUID provider = createCapableParticipant("temp-grant-" + suffix(), "Temp Grant", "OFFER_SERVICES");
        Map<?, ?> denied = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 5000L);
        assertThat(denied.toString()).contains("VALUE_ABOVE_LIMIT");

        UUID grant = temporaryGrant(provider, "ACCEPT_TRANSACTION", null, null, future());
        Map<?, ?> allowed = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 5000L);
        assertThat(allowed.get("decision")).isEqualTo("ALLOW_WITH_TEMPORARY_GRANT");
        assertThat(allowed.toString()).contains(grant.toString());

        UUID unrelated = createCapableParticipant("temp-unrelated-" + suffix(), "Temp Unrelated", "OFFER_SERVICES");
        Map<?, ?> unrelatedDenied = simulateCapability(unrelated, "ACCEPT_TRANSACTION", null, null, 5000L);
        assertThat(unrelatedDenied.toString()).contains("VALUE_ABOVE_LIMIT");

        post("/api/v1/capability-governance/temporary-grants/" + grant + "/revoke",
                Map.of("actor", "operator@example.com", "reason", "Grant no longer needed"), null);
        Map<?, ?> revoked = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 5000L);
        assertThat(revoked.toString()).contains("VALUE_ABOVE_LIMIT");

        UUID expiring = temporaryGrant(provider, "ACCEPT_TRANSACTION", null, null, future());
        jdbcTemplate.update("update temporary_capability_grants set expires_at = now() - interval '1 minute' where id = ?", expiring);
        post("/api/v1/capability-governance/temporary-grants/expire", Map.of(), null);
        assertThat(countRows("select count(*) from temporary_capability_grants where id = ? and status = 'EXPIRED'", expiring)).isEqualTo(1);

        UUID suspendedGrant = temporaryGrant(provider, "ACCEPT_TRANSACTION", null, null, future());
        updateStatus(provider, "SUSPENDED");
        Map<?, ?> suspended = simulateCapability(provider, "ACCEPT_TRANSACTION", null, null, 500L);
        assertThat(suspended.get("decision")).isNotEqualTo("ALLOW_WITH_TEMPORARY_GRANT");
        assertThat(suspended.toString()).contains("ACCOUNT_SUSPENDED");
        assertThat(getList("/api/v1/capability-governance/timeline").getBody().toString())
                .contains("TEMPORARY_GRANT_CREATED", "TEMPORARY_GRANT_REVOKED", "TEMPORARY_GRANT_EXPIRED");
    }
}
