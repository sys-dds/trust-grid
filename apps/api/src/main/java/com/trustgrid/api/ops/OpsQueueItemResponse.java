package com.trustgrid.api.ops;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OpsQueueItemResponse(UUID queueItemId, String queueType, String targetType, UUID targetId,
                                   String priority, String status, String reason, List<String> signals,
                                   String assignedTo, Instant createdAt) {
}
