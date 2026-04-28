package com.trustgrid.api.repair;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataRepairService {
    private final DataRepairRepository repository;
    private final OutboxRepository outboxRepository;

    public DataRepairService(DataRepairRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> generate() {
        int generated = repository.generate();
        UUID id = UUID.randomUUID();
        outboxRepository.insert("DATA_REPAIR", id, null, "DATA_REPAIR_RECOMMENDED", Map.of("generated", generated));
        return Map.of("generated", generated, "autoRepair", false);
    }

    public List<Map<String, Object>> list() {
        return repository.list();
    }

    public Map<String, Object> get(UUID id) {
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> approve(UUID id, Map<String, Object> request) {
        repository.approve(id, request);
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> apply(UUID id, Map<String, Object> request) {
        UUID actionId = repository.apply(id, request);
        outboxRepository.insert("DATA_REPAIR", actionId, null, "OPERATOR_DATA_REPAIR_APPLIED",
                Map.of("repairRecommendationId", id));
        return Map.of("repairRecommendationId", id, "operatorRepairActionId", actionId, "status", "APPLIED");
    }

    @Transactional
    public Map<String, Object> reject(UUID id, Map<String, Object> request) {
        UUID actionId = repository.reject(id, request);
        outboxRepository.insert("DATA_REPAIR", actionId, null, "OPERATOR_DATA_REPAIR_APPLIED",
                Map.of("repairRecommendationId", id, "rejected", true));
        return Map.of("repairRecommendationId", id, "operatorRepairActionId", actionId, "status", "REJECTED");
    }

    public List<Map<String, Object>> actions() {
        return repository.actions();
    }
}
