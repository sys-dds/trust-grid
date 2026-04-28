package com.trustgrid.api.analytics;

import java.util.Map;

public record DisputeAnalyticsResponse(int openDisputes, int resolvedDisputes, Map<String, Integer> outcomes,
                                       String analyticsBackend) {
}
