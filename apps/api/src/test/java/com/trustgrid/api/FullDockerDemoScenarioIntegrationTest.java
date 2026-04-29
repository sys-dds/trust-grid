package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FullDockerDemoScenarioIntegrationTest extends Tg221To240IntegrationTestSupport {

    @Test
    void fullDemoScenarioExercisesRuntimeSurfaceAndTrustControlPlane() {
        assertThat(get("/actuator/health").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/actuator/health/readiness").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/ping").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/node").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(get("/api/v1/system/dependencies").getStatusCode().is2xxSuccessful()).isTrue();

        UUID normal = createCapableParticipant("demo-normal-" + suffix(), "Demo Normal", "BUY");
        UUID trusted = createCapableParticipant("demo-trusted-" + suffix(), "Demo Trusted", "OFFER_SERVICES");
        UUID suspicious = createCapableParticipant("demo-suspicious-" + suffix(), "Demo Suspicious", "SELL_ITEMS");
        verifyParticipant(trusted);
        jdbcTemplate.update("update participants set trust_tier = 'TRUSTED' where id = ?", trusted);
        jdbcTemplate.update("update trust_profiles set trust_tier = 'TRUSTED', trust_score = 780 where participant_id = ?", trusted);

        UUID service = createListing(trusted, "SERVICE_OFFER", "TUTORING", "Demo service " + suffix(),
                2500L, null, serviceDetails());
        UUID item = createListing(suspicious, "ITEM_LISTING", "ELECTRONICS", "Demo item " + suffix(),
                200000L, null, itemDetails(true));
        publish(service);
        publish(item);
        assertThat(get("/api/v1/listings/trust-ranked-search?query=Demo&policyVersion=trust_balanced_v1")
                .getStatusCode().is2xxSuccessful()).isTrue();

        Flow flow = createCompletedServiceFlowBetween(normal, trusted, "docker-demo");
        UUID reviewId = review(flow.transactionId(), normal, trusted, 5, "Docker demo review", "docker-demo-review-" + suffix());
        post("/api/v1/participants/" + trusted + "/reputation/recalculate",
                Map.of("actor", "operator@example.com", "reason", "Docker demo reputation"), null);
        post("/api/v1/ops/moderator-actions/suppress-review-weight", Map.of(
                "targetType", "REVIEW",
                "targetId", reviewId.toString(),
                "actor", "operator@example.com",
                "reason", "Demo suspicious review suppression"
        ), null);

        Flow disputeFlow = createDisputableServiceFlow("docker-dispute");
        UUID dispute = openDispute(disputeFlow, "docker-dispute-" + suffix());
        recordEvidence("DISPUTE", dispute, disputeFlow.buyerId(), "USER_STATEMENT", "docker-evidence-" + suffix());
        post("/api/v1/disputes/" + dispute + "/status", Map.of(
                "newStatus", "UNDER_REVIEW",
                "actorParticipantId", disputeFlow.buyerId().toString(),
                "actor", "participant@example.com",
                "reason", "Demo dispute review"
        ), null);

        assertThat(post("/api/v1/transactions/" + flow.transactionId() + "/payment-boundary/request-release",
                Map.of("actor", "operator@example.com", "reason", "Docker demo release"), null)
                .getStatusCode().is2xxSuccessful()).isTrue();

        jdbcTemplate.update("""
                insert into risk_decisions (id, target_type, target_id, score, risk_level, decision, policy_version)
                values (?, 'PARTICIPANT', ?, 95, 'HIGH', 'BLOCK_TRANSACTION', 'deterministic_rules_v1')
                """, UUID.randomUUID(), suspicious);
        jdbcTemplate.update("update participants set risk_level = 'HIGH' where id = ?", suspicious);
        jdbcTemplate.update("update trust_profiles set risk_level = 'HIGH' where participant_id = ?", suspicious);
        var incident = post("/api/v1/trust-incidents", Map.of(
                "incidentType", "RISK_SPIKE",
                "severity", "HIGH",
                "title", "Docker demo incident",
                "description", "Demo incident",
                "actor", "operator@example.com",
                "reason", "Docker demo incident"
        ), null).getBody();
        UUID incidentId = UUID.fromString(incident.get("id").toString());
        assertThat(post("/api/v1/trust-incidents/" + incidentId + "/replay", Map.of(), null)
                .getBody().get("deterministic")).isEqualTo(true);

        createCapabilityPolicy("PUBLISH_LISTING", Map.of("maxRiskLevel", "MEDIUM"));
        assertThat(simulateCapability(suspicious, "PUBLISH_LISTING", "LISTING", item, 200000L).toString())
                .contains("RISK_LEVEL_TOO_HIGH");
        assertThat(simulateCapability(trusted, "PUBLISH_LISTING", "LISTING", service, 2500L).get("decision"))
                .isEqualTo("ALLOW");

        post("/api/v1/lineage/rebuild/full", operator(), null);
        post("/api/v1/consistency/checks/full", operator(), null);
        assertThat(getList("/api/v1/consistency/findings").getStatusCode().is2xxSuccessful()).isTrue();
    }
}
