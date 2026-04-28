package com.trustgrid.api.analytics;

import java.util.Map;

public record TrustMetricsResponse(Map<String, Integer> trustTiers, Map<String, Integer> verificationStatuses,
                                   int restrictedUsers, String analyticsBackend) {
}
