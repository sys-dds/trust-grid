package com.trustgrid.api.system;

import java.time.Instant;
import java.util.List;

public record SystemDependenciesResponse(
        String service,
        Instant timestamp,
        List<SystemDependencyResponse> dependencies
) {
}
