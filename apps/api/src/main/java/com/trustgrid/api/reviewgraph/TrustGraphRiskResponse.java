package com.trustgrid.api.reviewgraph;

import java.util.List;
import java.util.UUID;

public record TrustGraphRiskResponse(UUID participantId, int clusterCount, List<ReviewAbuseClusterResponse> clusters,
                                     List<String> signals, String policyVersion) {
}
