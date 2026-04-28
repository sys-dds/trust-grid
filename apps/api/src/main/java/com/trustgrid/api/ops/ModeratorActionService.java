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
        Map<String, Object> before = before(actionType, targetType, targetId, request);
        Map<String, Object> after = Map.of("action", actionType);
        switch (actionType) {
            case "HIDE_LISTING" -> {
                repository.hideListing(targetId);
                after = repository.listingState(targetId);
            }
            case "REQUEST_EVIDENCE" -> {
                String evidenceType = request.getOrDefault("evidenceType", "USER_STATEMENT").toString();
                UUID requirementId = repository.requestEvidence(targetType, targetId, evidenceType, reason);
                after = Map.of("requirementId", requirementId, "requirementCount",
                        repository.evidenceRequirementCount(targetType, targetId));
            }
            case "REQUEST_VERIFICATION" -> {
                UUID participantId = participantTarget(targetId, request);
                UUID restrictionId = repository.requestVerification(participantId, actor, reason);
                after = Map.of("participantId", participantId, "restrictionId", restrictionId,
                        "restrictionType", "REQUIRES_VERIFICATION");
            }
            case "RESTRICT_CAPABILITY" -> {
                UUID participantId = participantTarget(targetId, request);
                String capability = required(request, "capability");
                repository.restrictCapability(participantId, capability, actor, reason);
                targetId = participantId;
                targetType = "PARTICIPANT";
                after = repository.capabilityState(participantId, capability);
            }
            case "RESTORE_CAPABILITY" -> {
                UUID participantId = participantTarget(targetId, request);
                String capability = required(request, "capability");
                repository.restoreCapability(participantId, capability, actor, reason);
                targetId = participantId;
                targetType = "PARTICIPANT";
                after = repository.capabilityState(participantId, capability);
            }
            case "ESCALATE_DISPUTE" -> {
                repository.escalateDispute(targetId);
                after = repository.disputeState(targetId);
            }
            case "SUPPRESS_REVIEW_WEIGHT" -> {
                repository.suppressReview(targetId, reason);
                after = repository.reviewState(targetId);
            }
            case "RESTORE_REVIEW_WEIGHT" -> {
                repository.restoreReview(targetId, reason);
                after = repository.reviewState(targetId);
            }
            default -> {
            }
        }
        UUID actionId = repository.moderatorAction(actionType, targetType, targetId, actor, reason, before, after);
        outboxRepository.insert("MODERATOR_ACTION", actionId, null, "MODERATOR_ACTION_RECORDED", Map.of("actionType", actionType));
        if ("REQUEST_EVIDENCE".equals(actionType)) {
            outboxRepository.insert(targetType, targetId, null, "EVIDENCE_REQUIREMENT_CREATED", Map.of("source", "moderator_action"));
        }
        if ("SUPPRESS_REVIEW_WEIGHT".equals(actionType)) {
            outboxRepository.insert("REVIEW", targetId, null, "REVIEW_WEIGHT_SUPPRESSED", Map.of("actor", actor));
        }
        if ("RESTORE_REVIEW_WEIGHT".equals(actionType)) {
            outboxRepository.insert("REVIEW", targetId, null, "REVIEW_SUPPRESSED", Map.of("restored", true, "actor", actor));
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

    private Map<String, Object> before(String actionType, String targetType, UUID targetId, Map<String, Object> request) {
        return switch (actionType) {
            case "HIDE_LISTING" -> repository.listingState(targetId);
            case "RESTRICT_CAPABILITY", "RESTORE_CAPABILITY" ->
                    repository.capabilityState(participantTarget(targetId, request), required(request, "capability"));
            case "SUPPRESS_REVIEW_WEIGHT", "RESTORE_REVIEW_WEIGHT" -> repository.reviewState(targetId);
            case "ESCALATE_DISPUTE" -> repository.disputeState(targetId);
            case "REQUEST_EVIDENCE" -> Map.of("targetType", targetType, "targetId", targetId,
                    "requirementCount", repository.evidenceRequirementCount(targetType, targetId));
            default -> Map.of("targetType", targetType, "targetId", targetId);
        };
    }

    private UUID participantTarget(UUID targetId, Map<String, Object> request) {
        Object participantId = request.get("participantId");
        if (participantId != null && !participantId.toString().isBlank()) {
            return UUID.fromString(participantId.toString());
        }
        return targetId;
    }
}
