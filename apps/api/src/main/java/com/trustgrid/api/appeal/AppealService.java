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
        Map<String, Object> beforeState = Map.of("targetType", before.targetType(), "targetId", before.targetId());
        Map<String, Object> afterState = Map.of("decision", request.decision());
        repository.decide(id, request);
        if ("CAPABILITY_RESTORED".equals(request.decision())) {
            String capability = request.targetCapability();
            UUID participantId = before.participantId();
            if ("PARTICIPANT_CAPABILITY".equals(before.targetType())) {
                Map<String, Object> capabilityRow = repository.capabilityById(before.targetId());
                beforeState = capabilityRow;
                participantId = (UUID) capabilityRow.get("participant_id");
                capability = capabilityRow.get("capability").toString();
            }
            if (capability == null || capability.isBlank()) {
                Object metadataCapability = before.metadata().get("capability");
                if (metadataCapability == null || metadataCapability.toString().isBlank()) {
                    throw new IllegalArgumentException("targetCapability is required for capability appeal decisions");
                }
                capability = metadataCapability.toString();
            }
            repository.restoreCapability(participantId, capability, request.decidedBy(), request.reason());
            afterState = Map.of("participantId", participantId, "capability", capability, "status", "ACTIVE");
        }
        if ("RESTRICTION_REDUCED".equals(request.decision())) {
            if (!"PARTICIPANT_RESTRICTION".equals(before.targetType())) {
                throw new IllegalArgumentException("RESTRICTION_REDUCED requires PARTICIPANT_RESTRICTION target");
            }
            beforeState = repository.restrictionById(before.targetId());
            repository.reduceRestriction(before.participantId(), before.targetId(), request.decidedBy(), request.reason());
            afterState = Map.of("restrictionId", before.targetId(), "status", "REMOVED");
        }
        if ("EVIDENCE_REQUIRED".equals(request.decision())) {
            UUID requirementId = repository.createEvidenceRequirement(appealRequirementTarget(before.targetType()),
                    before.targetId(), request.reason());
            afterState = Map.of("requirementId", requirementId, "targetType", before.targetType(), "targetId", before.targetId());
        }
        if ("PERMANENT_SUSPENSION".equals(request.decision())) {
            UUID participantId = "PARTICIPANT".equals(before.targetType()) ? before.targetId() : before.participantId();
            repository.suspendParticipant(participantId, request.decidedBy(), request.reason());
            afterState = Map.of("participantId", participantId, "accountStatus", "SUSPENDED");
        }
        repository.mergeMetadata(id, Map.of("decisionBefore", beforeState, "decisionAfter", afterState));
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

    private String appealRequirementTarget(String targetType) {
        return switch (targetType) {
            case "LISTING" -> "LISTING";
            case "DISPUTE" -> "DISPUTE";
            case "PARTICIPANT", "PARTICIPANT_RESTRICTION", "PARTICIPANT_CAPABILITY" -> "PARTICIPANT";
            default -> "TRANSACTION";
        };
    }
}
