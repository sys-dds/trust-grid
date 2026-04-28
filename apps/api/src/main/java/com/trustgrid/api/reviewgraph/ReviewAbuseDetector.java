package com.trustgrid.api.reviewgraph;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ReviewAbuseDetector {
    public List<String> policySignals(String clusterType) {
        return List.of("deterministic_rules_v1", clusterType.toLowerCase());
    }

    public List<String> policySignals(String ruleKey, int threshold, int observedCount,
                                      List<UUID> participantIds, List<UUID> reviewIds, String severityReason) {
        return List.of(
                "policy_version=review_abuse_rules_v1",
                "rule_key=" + ruleKey,
                "threshold=" + threshold,
                "observed_count=" + observedCount,
                "participant_ids=" + participantIds,
                "review_ids=" + reviewIds,
                "severity_reason=" + severityReason
        );
    }
}
