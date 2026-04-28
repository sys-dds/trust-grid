package com.trustgrid.api.dispute;

import com.trustgrid.api.evidence.EvidenceService;
import com.trustgrid.api.evidence.EvidenceTargetType;
import com.trustgrid.api.evidence.EvidenceType;
import com.trustgrid.api.idempotency.IdempotencyService;
import com.trustgrid.api.reputation.ReputationService;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import com.trustgrid.api.transaction.TransactionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DisputeService {

    private final DisputeRepository repository;
    private final IdempotencyService idempotencyService;
    private final EvidenceService evidenceService;
    private final ReputationService reputationService;
    private final TransactionService transactionService;
    private final OutboxRepository outboxRepository;

    public DisputeService(DisputeRepository repository, IdempotencyService idempotencyService,
                          EvidenceService evidenceService, ReputationService reputationService,
                          TransactionService transactionService, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.evidenceService = evidenceService;
        this.reputationService = reputationService;
        this.transactionService = transactionService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public DisputeResponse create(UUID transactionId, String idempotencyKey, CreateDisputeRequest request) {
        return idempotencyService.run("dispute:create:" + transactionId, idempotencyKey,
                Map.of("transactionId", transactionId), request, "DISPUTE", this::get, () -> {
                    DisputeRepository.TransactionDisputeView tx = transaction(transactionId);
                    if (!eligibleForDispute(tx.status())) {
                        throw new ConflictException("Transaction is not eligible for dispute");
                    }
                    requireTransactionParticipant(tx, request.openedByParticipantId(), "open dispute");
                    if (repository.hasActiveDispute(transactionId)) {
                        throw new ConflictException("Transaction already has an active dispute");
                    }
                    UUID disputeId = repository.insertDispute(transactionId, request);
                    evidenceService.createRequirement(EvidenceTargetType.DISPUTE, disputeId, EvidenceType.USER_STATEMENT,
                            "RESOLVE_DISPUTE", "Dispute statement required", request.openedByParticipantId());
                    List<UUID> deadlineIds = roleAwareDeadlines(tx, request.disputeType()).stream()
                            .map(role -> repository.createDeadline(disputeId, role))
                            .toList();
                    outboxRepository.insert("DISPUTE", disputeId, request.openedByParticipantId(), "DISPUTE_OPENED",
                            Map.of("transactionId", transactionId, "disputeType", request.disputeType().name()));
                    deadlineIds.forEach(deadlineId -> outboxRepository.insert("DISPUTE", disputeId, request.openedByParticipantId(),
                            "DISPUTE_EVIDENCE_DEADLINE_CREATED", Map.of("deadlineId", deadlineId)));
                    return disputeId;
                });
    }

    public DisputeResponse get(UUID disputeId) {
        return repository.find(disputeId).orElseThrow(() -> new NotFoundException("Dispute not found"));
    }

    public List<DisputeResponse> search(UUID transactionId, DisputeStatus status) {
        return repository.search(transactionId, status);
    }

    @Transactional
    public DisputeResponse updateStatus(UUID disputeId, DisputeStatusUpdateRequest request) {
        DisputeResponse dispute = get(disputeId);
        if (resolved(dispute.status())) {
            throw new ConflictException("Resolved dispute cannot change status");
        }
        repository.updateStatus(disputeId, request.newStatus());
        outboxRepository.insert("DISPUTE", disputeId, dispute.openedByParticipantId(), "DISPUTE_STATUS_UPDATED",
                Map.of("from", dispute.status().name(), "to", request.newStatus().name(), "actor", request.actor()));
        return get(disputeId);
    }

    @Transactional
    public DisputeStatementResponse addStatement(UUID disputeId, DisputeStatementRequest request) {
        DisputeResponse dispute = get(disputeId);
        DisputeRepository.TransactionDisputeView tx = transaction(dispute.transactionId());
        if (request.statementType() != StatementType.MODERATOR_NOTE && request.statementType() != StatementType.SYSTEM_NOTE) {
            requireTransactionParticipant(tx, request.participantId(), "add dispute statement");
        }
        UUID statementId = repository.insertStatement(disputeId, request);
        outboxRepository.insert("DISPUTE", disputeId, request.participantId(), "DISPUTE_STATEMENT_ADDED",
                Map.of("statementId", statementId, "statementType", request.statementType().name()));
        return repository.statements(disputeId).stream()
                .filter(statement -> statement.statementId().equals(statementId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public DisputeResponse resolve(UUID disputeId, ResolveDisputeRequest request) {
        DisputeResponse dispute = get(disputeId);
        if (resolved(dispute.status())) {
            throw new ConflictException("Dispute is already resolved");
        }
        if (request.outcome() != DisputeOutcome.INSUFFICIENT_EVIDENCE
                && request.outcome() != DisputeOutcome.SAFETY_ESCALATION
                && repository.unsatisfiedRequirements(disputeId) > 0) {
            throw new ConflictException("Required dispute evidence is not satisfied");
        }
        DisputeStatus status = statusFor(request.outcome());
        repository.resolve(disputeId, status, request.outcome(), request.resolvedBy(), request.reason());
        DisputeRepository.TransactionDisputeView tx = transaction(dispute.transactionId());
        reputationService.recalculate(tx.requesterParticipantId(), "Dispute resolved");
        if (tx.providerParticipantId() != null) {
            reputationService.recalculate(tx.providerParticipantId(), "Dispute resolved");
        }
        outboxRepository.insert("DISPUTE", disputeId, dispute.openedByParticipantId(), "DISPUTE_RESOLVED",
                Map.of("outcome", request.outcome().name(), "status", status.name()));
        return get(disputeId);
    }

    public Map<String, Object> evidenceBundle(UUID disputeId) {
        DisputeResponse dispute = get(disputeId);
        DisputeRepository.TransactionDisputeView tx = transaction(dispute.transactionId());
        Map<String, Object> bundle = Map.ofEntries(
                Map.entry("dispute", dispute),
                Map.entry("transaction", transactionService.get(dispute.transactionId())),
                Map.entry("transactionTimeline", transactionService.timeline(dispute.transactionId(), null, null, null, 100, 0).events()),
                Map.entry("listingSnapshot", Map.of("listingId", tx.listingId())),
                Map.entry("requesterTrustSnapshot", reputationService.get(tx.requesterParticipantId())),
                Map.entry("providerTrustSnapshot", tx.providerParticipantId() == null ? Map.of() : reputationService.get(tx.providerParticipantId())),
                Map.entry("evidence", evidenceService.search(EvidenceTargetType.DISPUTE, disputeId)),
                Map.entry("evidenceRequirements", evidenceService.requirements(EvidenceTargetType.DISPUTE, disputeId)),
                Map.entry("riskDecisions", List.of()),
                Map.entry("moderationNotes", repository.statements(disputeId))
        );
        outboxRepository.insert("DISPUTE", disputeId, dispute.openedByParticipantId(), "EVIDENCE_BUNDLE_GENERATED",
                Map.of("sections", bundle.keySet()));
        return bundle;
    }

    public List<DisputeStatementResponse> statements(UUID disputeId) {
        get(disputeId);
        return repository.statements(disputeId);
    }

    public List<DisputeEvidenceDeadlineResponse> deadlines(UUID disputeId) {
        get(disputeId);
        return repository.deadlines(disputeId);
    }

    private DisputeRepository.TransactionDisputeView transaction(UUID transactionId) {
        return repository.transaction(transactionId).orElseThrow(() -> new NotFoundException("Transaction not found"));
    }

    private void requireTransactionParticipant(DisputeRepository.TransactionDisputeView tx, UUID participantId, String action) {
        if (participantId == null || (!participantId.equals(tx.requesterParticipantId()) && !participantId.equals(tx.providerParticipantId()))) {
            throw new MarketplaceActionForbiddenException(action + " requires a transaction participant");
        }
    }

    private boolean eligibleForDispute(String status) {
        return List.of("COMPLETION_CLAIMED", "CONFIRMATION_WINDOW_OPEN", "DELIVERED", "COMPLETED", "NO_SHOW_REPORTED").contains(status);
    }

    private boolean resolved(DisputeStatus status) {
        return List.of(DisputeStatus.RESOLVED_BUYER, DisputeStatus.RESOLVED_SELLER,
                DisputeStatus.RESOLVED_PROVIDER, DisputeStatus.SPLIT_DECISION,
                DisputeStatus.ESCALATED, DisputeStatus.CLOSED).contains(status);
    }

    private DisputeStatus statusFor(DisputeOutcome outcome) {
        return switch (outcome) {
            case BUYER_WINS -> DisputeStatus.RESOLVED_BUYER;
            case SELLER_WINS -> DisputeStatus.RESOLVED_SELLER;
            case PROVIDER_WINS -> DisputeStatus.RESOLVED_PROVIDER;
            case SPLIT_DECISION -> DisputeStatus.SPLIT_DECISION;
            case FRAUD_SUSPECTED, SAFETY_ESCALATION -> DisputeStatus.ESCALATED;
            case INSUFFICIENT_EVIDENCE -> DisputeStatus.CLOSED;
        };
    }

    private List<String> roleAwareDeadlines(DisputeRepository.TransactionDisputeView tx, DisputeType disputeType) {
        return switch (tx.transactionType()) {
            case "SERVICE_BOOKING" -> switch (disputeType) {
                case SERVICE_NOT_DELIVERED -> List.of("PROVIDER", "BUYER");
                case NO_SHOW -> List.of("REPORTED_SIDE", "REPORTER");
                case OFF_PLATFORM_PAYMENT_ATTEMPT, SAFETY_CONCERN -> List.of("REPORTER", "REPORTED_PARTICIPANT", "MODERATOR_REVIEW");
                default -> List.of("BUYER", "PROVIDER");
            };
            case "ITEM_PURCHASE" -> switch (disputeType) {
                case ITEM_NOT_RECEIVED -> List.of("SELLER", "BUYER");
                case ITEM_NOT_AS_DESCRIBED, COUNTERFEIT_SUSPECTED -> List.of("BUYER", "SELLER");
                case OFF_PLATFORM_PAYMENT_ATTEMPT, SAFETY_CONCERN -> List.of("REPORTER", "REPORTED_PARTICIPANT", "MODERATOR_REVIEW");
                default -> List.of("BUYER", "SELLER");
            };
            case "ERRAND" -> switch (disputeType) {
                case NO_SHOW, SERVICE_NOT_DELIVERED -> List.of("REQUESTER", "PROVIDER");
                case OFF_PLATFORM_PAYMENT_ATTEMPT, SAFETY_CONCERN -> List.of("REPORTER", "REPORTED_PARTICIPANT", "MODERATOR_REVIEW");
                default -> List.of("REQUESTER", "PROVIDER");
            };
            case "SHOPPING_REQUEST" -> switch (disputeType) {
                case WRONG_ITEM_PURCHASED, SHOPPER_DID_NOT_BUY -> List.of("SHOPPER", "BUYER");
                case OFF_PLATFORM_PAYMENT_ATTEMPT, SAFETY_CONCERN -> List.of("REPORTER", "REPORTED_PARTICIPANT", "MODERATOR_REVIEW");
                default -> List.of("SHOPPER", "BUYER");
            };
            default -> List.of("REPORTER", "REPORTED_PARTICIPANT");
        };
    }
}
