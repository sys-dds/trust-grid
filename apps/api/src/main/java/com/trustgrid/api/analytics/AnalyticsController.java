package com.trustgrid.api.analytics;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalyticsController {
    private final AnalyticsService service;

    public AnalyticsController(AnalyticsService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/analytics/ingest-events")
    public Map<String, Object> ingest() {
        return service.ingest();
    }

    @GetMapping("/api/v1/analytics/trust-metrics")
    public TrustMetricsResponse trustMetrics() {
        return service.trustMetrics();
    }

    @GetMapping("/api/v1/analytics/fraud-metrics")
    public FraudMetricsResponse fraudMetrics() {
        return service.fraudMetrics();
    }

    @GetMapping("/api/v1/analytics/marketplace-health")
    public MarketplaceHealthMetricsResponse marketplaceHealth() {
        return service.marketplaceHealth();
    }

    @GetMapping("/api/v1/analytics/ranking")
    public RankingAnalyticsResponse ranking() {
        return service.ranking();
    }

    @GetMapping("/api/v1/analytics/disputes")
    public DisputeAnalyticsResponse disputes() {
        return service.disputes();
    }
}
