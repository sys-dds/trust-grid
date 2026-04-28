package com.trustgrid.api.analytics;

public record MarketplaceHealthMetricsResponse(int participants, int liveListings, int openDisputes,
                                               int moderationBacklog, int evidenceBacklog, String analyticsBackend) {
}
