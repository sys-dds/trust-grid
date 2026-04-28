package com.trustgrid.api.analytics;

public record RankingAnalyticsResponse(int rankingRuns, int suppressions, String analyticsBackend) {
}
