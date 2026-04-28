package com.trustgrid.api.ops;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OpsQueueService {
    private final OpsQueueRepository repository;
    private final OutboxRepository outboxRepository;

    public OpsQueueService(OpsQueueRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    public List<OpsQueueItemResponse> search(String queueType, String status) {
        return repository.search(queueType, status);
    }

    public OpsQueueItemResponse get(UUID id) {
        return repository.get(id);
    }

    @Transactional
    public Map<String, Object> rebuild() {
        int created = repository.rebuild();
        outboxRepository.insert("OPS_QUEUE", UUID.randomUUID(), null, "OPS_QUEUE_ITEM_CREATED", Map.of("createdOrExisting", created));
        return Map.of("createdOrExisting", created);
    }

    @Transactional
    public OpsQueueItemResponse create(CreateOpsQueueItemRequest request) {
        UUID id = repository.insert(request);
        outboxRepository.insert("OPS_QUEUE", id, null, "OPS_QUEUE_ITEM_CREATED", Map.of("queueType", request.queueType()));
        return repository.get(id);
    }

    @Transactional
    public OpsQueueItemResponse update(UUID id, Map<String, Object> request) {
        repository.updateStatus(id, required(request, "status"));
        outboxRepository.insert("OPS_QUEUE", id, null, "OPS_QUEUE_ITEM_UPDATED", Map.of("status", request.get("status")));
        return repository.get(id);
    }

    String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
