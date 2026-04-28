package com.trustgrid.api.analytics;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {
    private final AnalyticsRepository repository;
    private final OutboxRepository outboxRepository;

    public AnalyticsService(AnalyticsRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> ingest() {
        int events = repository.ingestEvents();
        outboxRepository.insert("ANALYTICS", UUID.randomUUID(), null, "ANALYTICS_EVENT_INGESTED", Map.of("events", events));
        return Map.of("eventsIngested", events, "analyticsBackend", "POSTGRES_FALLBACK");
    }

    public TrustMetricsResponse trustMetrics() {
        return new TrustMetricsResponse(repository.grouped("select trust_tier, count(*) from participants group by trust_tier"),
                repository.grouped("select verification_status, count(*) from participants group by verification_status"),
                repository.count("select count(*) from participants where account_status in ('RESTRICTED','SUSPENDED')"),
                "POSTGRES_FALLBACK");
    }

    public FraudMetricsResponse fraudMetrics() {
        return new FraudMetricsResponse(repository.count("select count(*) from review_abuse_clusters"),
                repository.count("select count(*) from off_platform_contact_reports"),
                repository.count("select count(*) from risk_decisions where decision = 'BLOCK_TRANSACTION'"),
                repository.count("select count(*) from risk_decisions where risk_level in ('HIGH','CRITICAL')"),
                "deterministic_rules_v1");
    }

    public MarketplaceHealthMetricsResponse marketplaceHealth() {
        return new MarketplaceHealthMetricsResponse(repository.count("select count(*) from participants"),
                repository.count("select count(*) from marketplace_listings where status = 'LIVE'"),
                repository.count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                repository.count("select count(*) from marketplace_ops_queue_items where status in ('OPEN','IN_REVIEW')"),
                repository.count("select count(*) from evidence_requirements where satisfied = false"),
                "POSTGRES_FALLBACK");
    }

    public RankingAnalyticsResponse ranking() {
        return new RankingAnalyticsResponse(repository.count("select count(*) from ranking_decision_logs"),
                repository.count("select count(*) from marketplace_listings where status != 'LIVE'"), "POSTGRES_FALLBACK");
    }

    public DisputeAnalyticsResponse disputes() {
        return new DisputeAnalyticsResponse(repository.count("select count(*) from marketplace_disputes where status in ('OPEN','UNDER_REVIEW','ESCALATED')"),
                repository.count("select count(*) from marketplace_disputes where resolved_at is not null"),
                repository.grouped("select coalesce(outcome, 'OPEN'), count(*) from marketplace_disputes group by coalesce(outcome, 'OPEN')"),
                "POSTGRES_FALLBACK");
    }
}
