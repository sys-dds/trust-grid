package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceChainOfCustodyIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void evidenceVersionCustodyAndTamperCheckWork() {
        Flow flow = createCompletedServiceFlow("custody-" + suffix());
        UUID evidence = recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "BEFORE_PHOTO", "custody-" + suffix());
        post("/api/v1/evidence/" + evidence + "/versions", Map.of("hash", "abc", "actor", "operator@example.com", "reason", "Version"), null);
        assertThat(getList("/api/v1/evidence/" + evidence + "/versions").getBody()).hasSize(1);
        assertThat(post("/api/v1/evidence/" + evidence + "/tamper-check", Map.of("expectedHash", "tampered", "actor", "operator@example.com", "reason", "Check"), null).getBody().get("hashMatched")).isEqualTo(false);
        assertThat(getList("/api/v1/evidence/" + evidence + "/custody-chain").getBody()).isNotEmpty();
    }
}
