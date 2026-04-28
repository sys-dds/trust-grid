package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskDecisionRulesIntegrationTest extends EvidenceDisputeRiskIntegrationTestSupport {

    @Test
    void riskDecisionsExplainSemanticsReportsAndSyntheticSignals() {
        Flow flow = createCompletedServiceFlow("risk-rules");

        Map<?, ?> allow = get("/api/v1/risk/explain?targetType=PARTICIPANT&targetId=" + flow.buyerId()).getBody();
        assertThat(allow.get("decision")).isEqualTo("ALLOW");

        Map<?, ?> report = post("/api/v1/transactions/" + flow.transactionId() + "/off-platform-contact-reports", Map.of(
                "reporterParticipantId", flow.buyerId().toString(),
                "reportedParticipantId", flow.providerId().toString(),
                "reportText", "They asked to move contact outside TrustGrid.",
                "reason", "Off-platform contact attempt"
        ), "risk-report-" + suffix()).getBody();
        assertThat(report.get("decision")).isEqualTo("REQUIRE_MANUAL_REVIEW");

        Map<?, ?> signal = post("/api/v1/participants/" + flow.providerId() + "/synthetic-risk-signals", Map.of(
                "signalType", "DEVICE_HASH_SIMULATED",
                "signalHash", "sha256-demo-cluster-" + suffix(),
                "riskWeight", 20,
                "source", "SIMULATED_TEST_SIGNAL",
                "retentionUntil", "2026-12-31T00:00:00Z",
                "reason", "Synthetic cluster simulation"
        ), "risk-signal-" + suffix()).getBody();
        assertThat(signal.get("riskLevel")).isEqualTo("HIGH");

        Map<?, ?> explanation = get("/api/v1/risk/explain?targetType=TRANSACTION&targetId=" + flow.transactionId()).getBody();
        assertThat(explanation.get("matchedRules").toString()).contains("off_platform");
        assertThat(explanation.get("nextSteps").toString()).contains("manual_review");
        assertThat(countRows("select count(*) from marketplace_events where event_type = 'RISK_ACTION_RECOMMENDED'")).isGreaterThanOrEqualTo(2);
    }
}
