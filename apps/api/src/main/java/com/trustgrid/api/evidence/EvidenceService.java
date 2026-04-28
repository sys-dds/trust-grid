package com.trustgrid.api.evidence;

import com.trustgrid.api.idempotency.IdempotencyService;
import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EvidenceService {

    private final EvidenceRepository repository;
    private final IdempotencyService idempotencyService;
    private final OutboxRepository outboxRepository;

    public EvidenceService(EvidenceRepository repository, IdempotencyService idempotencyService,
                           OutboxRepository outboxRepository) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public EvidenceResponse create(String idempotencyKey, CreateEvidenceRequest request) {
        return idempotencyService.run("evidence:create:" + request.targetType() + ":" + request.targetId(),
                idempotencyKey, Map.of("targetId", request.targetId(), "targetType", request.targetType()), request,
                "EVIDENCE", this::get, () -> {
                    if (!repository.targetExists(request.targetType(), request.targetId())) {
                        throw new NotFoundException("Evidence target not found");
                    }
                    UUID id = repository.insertEvidence(request);
                    outboxRepository.insert("EVIDENCE", id, request.uploadedByParticipantId(), "EVIDENCE_RECORDED",
                            Map.of("targetType", request.targetType().name(), "targetId", request.targetId(), "evidenceType", request.evidenceType().name()));
                    return id;
                });
    }

    public EvidenceResponse get(UUID evidenceId) {
        return repository.findEvidence(evidenceId).orElseThrow(() -> new NotFoundException("Evidence not found"));
    }

    public List<EvidenceResponse> search(EvidenceTargetType targetType, UUID targetId) {
        return repository.searchEvidence(targetType, targetId);
    }

    public List<EvidenceRequirementResponse> requirements(EvidenceTargetType targetType, UUID targetId) {
        return repository.searchRequirements(targetType, targetId);
    }

    @Transactional
    public EvidenceRequirementResponse satisfy(UUID requirementId, String idempotencyKey, SatisfyEvidenceRequirementRequest request) {
        return idempotencyService.run("evidence-requirement:satisfy:" + requirementId, idempotencyKey,
                Map.of("requirementId", requirementId), request, "EVIDENCE_REQUIREMENT",
                this::requirement, () -> {
                    EvidenceRequirementResponse requirement = requirement(requirementId);
                    EvidenceResponse evidence = get(request.evidenceId());
                    if (evidence.targetType() != requirement.targetType() || !evidence.targetId().equals(requirement.targetId())) {
                        throw new NotFoundException("Evidence does not match requirement target");
                    }
                    repository.satisfyRequirement(requirementId, request.evidenceId());
                    outboxRepository.insert("EVIDENCE_REQUIREMENT", requirementId, evidence.uploadedByParticipantId(),
                            "EVIDENCE_REQUIREMENT_SATISFIED", Map.of("evidenceId", request.evidenceId()));
                    return requirementId;
                });
    }

    public EvidenceRequirementResponse requirement(UUID requirementId) {
        return repository.findRequirement(requirementId).orElseThrow(() -> new NotFoundException("Evidence requirement not found"));
    }

    @Transactional
    public UUID createRequirement(EvidenceTargetType targetType, UUID targetId, EvidenceType evidenceType,
                                  String requiredBeforeAction, String reason, UUID participantId) {
        UUID id = repository.createRequirement(targetType, targetId, evidenceType, requiredBeforeAction, reason);
        outboxRepository.insert("EVIDENCE_REQUIREMENT", id, participantId, "EVIDENCE_REQUIREMENT_CREATED",
                Map.of("targetType", targetType.name(), "targetId", targetId, "evidenceType", evidenceType.name()));
        return id;
    }
}
