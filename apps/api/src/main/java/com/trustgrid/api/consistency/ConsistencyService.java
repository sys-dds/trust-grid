package com.trustgrid.api.consistency;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsistencyService {
    private final ConsistencyRepository repository;
    private final OutboxRepository outboxRepository;

    public ConsistencyService(ConsistencyRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> run(String checkType, Map<String, Object> request) {
        UUID runId = repository.run(checkType, request);
        outboxRepository.insert("CONSISTENCY_CHECK", runId, null, "CONSISTENCY_CHECK_RUN", Map.of("checkType", checkType));
        return Map.of("checkRunId", runId, "checkType", checkType, "status", "SUCCEEDED", "autoRepair", false);
    }

    public List<Map<String, Object>> runs() {
        return repository.runs();
    }

    public List<Map<String, Object>> findings() {
        return repository.findings();
    }
}
