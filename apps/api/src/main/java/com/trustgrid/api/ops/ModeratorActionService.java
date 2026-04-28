package com.trustgrid.api.ops;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModeratorActionService {
    private final OpsQueueRepository repository;
    private final OutboxRepository outboxRepository;

    public ModeratorActionService(OpsQueueRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public Map<String, Object> act(String actionType, Map<String, Object> request) {
        String actor = required(request, "actor");
        String reason = required(request, "reason");
        String targetType = required(request, "targetType");
        UUID targetId = UUID.fromString(required(request, "targetId"));
        Map<String, Object> before = Map.of("targetType", targetType, "targetId", targetId.toString());
        switch (actionType) {
            case "HIDE_LISTING" -> repository.hideListing(targetId);
            case "REQUEST_EVIDENCE" -> repository.requestEvidence(targetId);
            case "SUPPRESS_REVIEW_WEIGHT" -> repository.suppressReview(targetId, reason);
            default -> {
            }
        }
        UUID actionId = repository.moderatorAction(actionType, targetType, targetId, actor, reason, before, Map.of("action", actionType));
        outboxRepository.insert("MODERATOR_ACTION", actionId, null, "MODERATOR_ACTION_RECORDED", Map.of("actionType", actionType));
        if ("REQUEST_EVIDENCE".equals(actionType)) {
            outboxRepository.insert(targetType, targetId, null, "EVIDENCE_REQUIREMENT_CREATED", Map.of("source", "moderator_action"));
        }
        if ("SUPPRESS_REVIEW_WEIGHT".equals(actionType)) {
            outboxRepository.insert("REVIEW", targetId, null, "REVIEW_WEIGHT_SUPPRESSED", Map.of("actor", actor));
        }
        if ("RESTRICT_CAPABILITY".equals(actionType) || "RESTORE_CAPABILITY".equals(actionType)) {
            outboxRepository.insert("PARTICIPANT", targetId, targetId, "ACCOUNT_RESTRICTION_WORKFLOW_UPDATED", Map.of("actionType", actionType));
        }
        return Map.of("moderatorActionId", actionId, "actionType", actionType);
    }

    public List<Map<String, Object>> actions() {
        return repository.moderatorActions();
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
