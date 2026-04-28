package com.trustgrid.api.reviewgraph;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewAbuseClusterResponse(UUID clusterId, String clusterType, String severity, String status,
                                         String policyVersion, String summary, List<String> signals,
                                         List<UUID> memberParticipantIds, List<UUID> reviewIds, Instant createdAt) {
}
