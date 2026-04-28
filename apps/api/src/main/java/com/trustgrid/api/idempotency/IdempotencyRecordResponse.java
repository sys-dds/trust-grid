package com.trustgrid.api.idempotency;

import java.util.UUID;

public record IdempotencyRecordResponse(
        UUID id,
        String scope,
        String idempotencyKey,
        String requestHash,
        String resourceType,
        UUID resourceId
) {
}
