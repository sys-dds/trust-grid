package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BreakGlassGovernanceIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void breakGlassIsScopedAuditedExpiringAndClosedAccountCannotBeBypassed() {
        createCapabilityPolicy("PUBLISH_LISTING", Map.of());
        UUID seller = createCapableParticipant("break-glass-" + suffix(), "Break Glass", "SELL_ITEMS");
        UUID listing = createListing(seller, "ITEM_LISTING", "ELECTRONICS", "Emergency item " + suffix(),
                2000L, null, itemDetails(false));
        publish(listing);
        updateStatus(seller, "SUSPENDED");

        Map<?, ?> suspended = simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 2000L);
        assertThat(suspended.toString()).contains("ACCOUNT_SUSPENDED");
        UUID override = breakGlass(seller, "PUBLISH_LISTING", "LISTING", listing, future());
        Map<?, ?> allowed = simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 2000L);
        assertThat(allowed.get("decision")).isEqualTo("ALLOW_WITH_BREAK_GLASS");
        assertThat(allowed.toString()).contains(override.toString());

        Map<?, ?> unrelatedAction = simulateCapability(seller, "ACCEPT_TRANSACTION", null, null, 2000L);
        assertThat(unrelatedAction.get("decision")).isNotEqualTo("ALLOW_WITH_BREAK_GLASS");

        post("/api/v1/capability-governance/break-glass/" + override + "/revoke",
                Map.of("actor", "senior-operator@example.com", "reason", "Emergency ended"), null);
        assertThat(simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 2000L).get("decision"))
                .isNotEqualTo("ALLOW_WITH_BREAK_GLASS");

        UUID expiring = breakGlass(seller, "PUBLISH_LISTING", "LISTING", listing, future());
        jdbcTemplate.update("update break_glass_capability_actions set expires_at = now() - interval '1 minute' where id = ?", expiring);
        post("/api/v1/capability-governance/break-glass/expire", Map.of(), null);
        assertThat(countRows("select count(*) from break_glass_capability_actions where id = ? and status = 'EXPIRED'", expiring)).isEqualTo(1);

        updateStatus(seller, "CLOSED");
        breakGlass(seller, "PUBLISH_LISTING", "LISTING", listing, future());
        Map<?, ?> closed = simulateCapability(seller, "PUBLISH_LISTING", "LISTING", listing, 2000L);
        assertThat(closed.get("decision")).isNotEqualTo("ALLOW_WITH_BREAK_GLASS");
        assertThat(closed.toString()).contains("ACCOUNT_CLOSED");
        assertThat(getList("/api/v1/capability-governance/timeline").getBody().toString())
                .contains("BREAK_GLASS_CREATED", "BREAK_GLASS_REVOKED", "BREAK_GLASS_EXPIRED");
    }
}
