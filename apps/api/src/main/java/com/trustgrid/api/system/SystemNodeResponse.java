package com.trustgrid.api.system;

import java.time.Instant;

public record SystemNodeResponse(
        String service,
        String nodeId,
        String javaVersion,
        String runtimeMode,
        Instant timestamp
) {
}
