package com.trustgrid.api.reviewgraph;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewAbuseDetector {
    public List<String> policySignals(String clusterType) {
        return List.of("deterministic_rules_v1", clusterType.toLowerCase());
    }
}
