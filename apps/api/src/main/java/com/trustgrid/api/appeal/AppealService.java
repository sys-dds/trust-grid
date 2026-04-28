package com.trustgrid.api.appeal;

import com.trustgrid.api.ops.CreateOpsQueueItemRequest;
import com.trustgrid.api.ops.OpsQueueService;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppealService {
    private final AppealRepository repository;
    private final OpsQueueService opsQueueService;
    private final OutboxRepository outboxRepository;

    public AppealService(AppealRepository repository, OpsQueueService opsQueueService, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.opsQueueService = opsQueueService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public AppealResponse create(UUID participantId, CreateAppealRequest request) {
        UUID id = repository.create(participantId, request);
        opsQueueService.create(new CreateOpsQueueItemRequest("APPEALS", "APPEAL", id, "MEDIUM",
                "Participant appeal opened", List.of("appeal_opened")));
        outboxRepository.insert("APPEAL", id, participantId, "APPEAL_OPENED", Map.of("targetType", request.targetType()));
        return repository.get(id);
    }

    @Transactional
    public AppealResponse status(UUID id, Map<String, Object> request) {
        repository.status(id, request.getOrDefault("status", "UNDER_REVIEW").toString());
        return repository.get(id);
    }

    @Transactional
    public AppealResponse decide(UUID id, DecideAppealRequest request) {
        AppealResponse before = repository.get(id);
        repository.decide(id, request);
        if ("CAPABILITY_RESTORED".equals(request.decision())) {
            repository.restoreCapabilities(before.participantId());
        }
        if ("RESTRICTION_REDUCED".equals(request.decision())) {
            repository.reduceRestrictions(before.participantId());
        }
        outboxRepository.insert("APPEAL", id, before.participantId(), "APPEAL_DECIDED",
                Map.of("decision", request.decision()));
        return repository.get(id);
    }

    public List<AppealResponse> list(String status) {
        return repository.list(status);
    }

    public AppealResponse get(UUID id) {
        return repository.get(id);
    }
}
