package com.trustgrid.api.simulator;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScamSimulatorService {
    private final ScamSimulatorRepository repository;
    private final OutboxRepository outboxRepository;

    public ScamSimulatorService(ScamSimulatorRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> scam(String version, Map<String, Object> request) {
        String type = request.getOrDefault("simulationType", version.equals("v1") ? "FAKE_REVIEW_RING" : "MULTI_ACCOUNT_CAMPAIGN").toString();
        int seed = Math.min(((Number) request.getOrDefault("seedSize", 3)).intValue(), 25);
        UUID runId = repository.run(type, actor(request), reason(request), seed,
                Map.of("version", version, "boundedSyntheticData", true, "rawIdentifiersStored", false));
        repository.syntheticCluster(type, runId);
        outboxRepository.insert("SCAM_SIMULATION", runId, null, "SCAM_SIMULATION_RUN", Map.of("simulationType", type));
        return Map.of("simulationRunId", runId, "simulationType", type, "seedSize", seed, "status", "SUCCEEDED");
    }

    @Transactional
    public Map<String, Object> seedScale(Map<String, Object> request) {
        Map<String, Object> caps = Map.of(
                "participants", repository.capped(request, "participants", 10000),
                "listings", repository.capped(request, "listings", 50000),
                "transactions", repository.capped(request, "transactions", 100000),
                "reviews", repository.capped(request, "reviews", 20000),
                "disputes", repository.capped(request, "disputes", 5000),
                "fraudClusters", repository.capped(request, "fraudClusters", 100)
        );
        UUID runId = repository.run("LOW_VALUE_REVIEW_FARMING", actor(request), reason(request), 0, caps);
        outboxRepository.insert("SCAM_SIMULATION", runId, null, "SCAM_SIMULATION_RUN", caps);
        return Map.of("simulationRunId", runId, "cappedCounts", caps, "status", "SUCCEEDED");
    }

    @Transactional
    public Map<String, Object> benchmark(Map<String, Object> request) {
        String type = request.getOrDefault("benchmarkType", "SEARCH_LATENCY").toString();
        UUID runId = repository.benchmark(type, actor(request), reason(request),
                Map.of("durationMs", 1, "recordsObserved", 0, "deterministicBenchmark", true));
        outboxRepository.insert("BENCHMARK", runId, null, "BENCHMARK_RUN", Map.of("benchmarkType", type));
        return Map.of("benchmarkRunId", runId, "benchmarkType", type, "status", "SUCCEEDED");
    }

    private String actor(Map<String, Object> request) {
        return request.getOrDefault("requestedBy", request.getOrDefault("actor", "operator@example.com")).toString();
    }

    private String reason(Map<String, Object> request) {
        return request.getOrDefault("reason", "Synthetic trust-safety proof").toString();
    }
}
