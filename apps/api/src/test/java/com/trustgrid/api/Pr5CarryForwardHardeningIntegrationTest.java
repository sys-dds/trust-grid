package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Pr5CarryForwardHardeningIntegrationTest extends Tg101To160IntegrationTestSupport {

    @Test
    void reputationGetIsReadOnlyAndRecalculateIsExplicit() {
        UUID participant = createCapableParticipant("read-only-rep-" + suffix(), "Read Only Rep", "BUY");
        int snapshotsBefore = countRows("select count(*) from reputation_snapshots where participant_id = ?", participant);
        get("/api/v1/participants/" + participant + "/reputation");
        get("/api/v1/participants/" + participant + "/reputation");
        assertThat(countRows("select count(*) from reputation_snapshots where participant_id = ?", participant)).isEqualTo(snapshotsBefore);

        post("/api/v1/participants/" + participant + "/reputation/recalculate",
                Map.of("actor", "system", "reason", "Manual recalculation"), null);
        assertThat(countRows("select count(*) from reputation_snapshots where participant_id = ?", participant)).isGreaterThan(snapshotsBefore);
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'REPUTATION_RECALCULATED'")).isPositive();
    }

    @Test
    void rankingReplayRecomputesFromStoredSnapshotAndExplanationsStayDeterministic() {
        Flow flow = createCompletedServiceFlow("replay-real");
        Map<?, ?> ranking = get("/api/v1/listings/trust-ranked-search?query=replay-real&policyVersion=trust_balanced_v1").getBody();
        UUID rankingDecisionId = UUID.fromString((String) ranking.get("rankingDecisionId"));
        Map<?, ?> replay = post("/api/v1/listings/ranking-decisions/" + rankingDecisionId + "/replay", Map.of(), null).getBody();
        assertThat(replay.get("matched")).isEqualTo(true);
        assertThat(replay.get("reasonsSummary").toString()).contains("recomputed");

        Map<?, ?> explain = get("/api/v1/risk/explain?targetType=TRANSACTION&targetId=" + flow.transactionId()).getBody();
        assertThat(explain.get("policyVersion")).isEqualTo("risk_rules_v1");
        assertThat(explain.toString()).doesNotContain("AI", "model " + "inference", "learned " + "ranking");
    }

    @Test
    void e2eLifecycleUsesPublicApisAndDisputeDeadlinesAreRoleAware() {
        Flow flow = createDisputableServiceFlow("role-aware");
        post("/api/v1/transactions/" + flow.transactionId() + "/confirm-completion",
                action(flow.buyerId()), "role-aware-confirm-" + suffix());
        assertThat(get("/api/v1/transactions/" + flow.transactionId()).getBody().get("status")).isEqualTo("COMPLETED");

        UUID dispute = openDispute(flow, "role-aware-dispute-" + suffix());
        java.util.List deadlines = getList("/api/v1/disputes/" + dispute + "/deadlines").getBody();
        assertThat(deadlines.toString()).contains("PROVIDER").contains("BUYER");
    }
}
