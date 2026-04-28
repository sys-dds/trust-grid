package com.trustgrid.api.reviewgraph;

import java.time.Instant;
import java.util.UUID;

public record ReviewGraphEdgeResponse(UUID edgeId, UUID reviewId, UUID transactionId, UUID reviewerParticipantId,
                                      UUID reviewedParticipantId, int rating, long transactionValueCents,
                                      String normalizedTextHash, Instant createdAt) {
}
