package com.trustgrid.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceAccessDisclosureRetentionIntegrationTest extends Tg241To360IntegrationTestSupport {
    @Test
    void evidenceAccessRetentionLegalHoldAndDisclosureBundleWork() {
        Flow flow = createCompletedServiceFlow("access-" + suffix());
        UUID evidence = recordEvidence("TRANSACTION", flow.transactionId(), flow.buyerId(), "BEFORE_PHOTO", "access-" + suffix());
        assertThat(post("/api/v1/evidence/" + evidence + "/access-simulate", Map.of("requestedBy", "operator@example.com", "accessPurpose", "CASE_REVIEW", "redactionRequired", true), null).getBody().get("decision")).isEqualTo("ALLOW_REDACTED");
        post("/api/v1/evidence/" + evidence + "/retention", Map.of("retentionClass", "STANDARD"), null);
        assertThat(post("/api/v1/evidence/" + evidence + "/legal-hold", Map.of("actor", "operator@example.com", "reason", "Hold"), null).getBody().get("legalHold")).isEqualTo(true);
        assertThat(post("/api/v1/evidence/disclosure-bundles", Map.of("targetType", "TRANSACTION", "targetId", flow.transactionId().toString(), "requestedBy", "operator@example.com", "reason", "Bundle"), null).getBody().get("bundleId")).isNotNull();
    }
}
