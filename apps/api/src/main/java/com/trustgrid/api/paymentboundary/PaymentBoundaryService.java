package com.trustgrid.api.paymentboundary;

import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentBoundaryService {
    private static final Set<String> REFUND_WORTHY_OUTCOMES = Set.of(
            "BUYER_WINS", "SPLIT_DECISION", "FRAUD_SUSPECTED", "SAFETY_ESCALATION"
    );

    private final PaymentBoundaryRepository repository;
    private final OutboxRepository outboxRepository;

    public PaymentBoundaryService(PaymentBoundaryRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    public Map<String, Object> get(UUID transactionId) {
        return Map.of("transactionId", transactionId, "states", repository.states(transactionId), "events", repository.events(transactionId));
    }

    @Transactional
    public Map<String, Object> release(UUID transactionId, Map<String, Object> request) {
        if (!"COMPLETED".equals(repository.transactionStatus(transactionId))) {
            throw new ConflictException("Completed transaction required for release request");
        }
        if (repository.unresolvedDisputeExists(transactionId)) {
            throw new ConflictException("Release request requires no unresolved dispute");
        }
        return boundary(transactionId, "RELEASE_REQUESTED", "MARKETPLACE_FUNDS_RELEASE_REQUESTED", request);
    }

    @Transactional
    public Map<String, Object> refund(UUID transactionId, Map<String, Object> request) {
        Map<String, Object> dispute = repository.latestResolvedDispute(transactionId)
                .orElseThrow(() -> new ConflictException("Resolved dispute required for refund request"));
        String outcome = String.valueOf(dispute.get("outcome"));
        boolean explicitModeratorRecommendation = request.getOrDefault("reason", "").toString().toLowerCase().contains("refund recommended");
        if (!REFUND_WORTHY_OUTCOMES.contains(outcome)
                && !("INSUFFICIENT_EVIDENCE".equals(outcome) && explicitModeratorRecommendation)) {
            throw new ConflictException("Refund request requires a refund-worthy dispute outcome");
        }
        if (!repository.resolvedDisputeExists(transactionId)) {
            throw new ConflictException("Resolved dispute required for refund request");
        }
        return boundary(transactionId, "REFUND_REQUESTED", "MARKETPLACE_REFUND_REQUESTED", request,
                Map.of("disputeId", dispute.get("id"), "outcome", outcome));
    }

    @Transactional
    public Map<String, Object> hold(UUID transactionId, Map<String, Object> request) {
        if (!repository.hasPayoutHoldRiskEvidence(transactionId)) {
            throw new ConflictException("Payout hold request requires high-risk participant, transaction, dispute, or safety evidence");
        }
        return boundary(transactionId, "PAYOUT_HOLD_REQUESTED", "MARKETPLACE_PAYOUT_HOLD_REQUESTED", request);
    }

    @Transactional
    public Map<String, Object> close(UUID transactionId, Map<String, Object> request) {
        if (!repository.hasPriorBoundaryDecision(transactionId)) {
            throw new ConflictException("Payment boundary close requires a prior release, refund, or hold request");
        }
        return boundary(transactionId, "TRANSACTION_CLOSED", "MARKETPLACE_TRANSACTION_CLOSED", request);
    }

    private Map<String, Object> boundary(UUID transactionId, String state, String eventType, Map<String, Object> request) {
        return boundary(transactionId, state, eventType, request, Map.of());
    }

    private Map<String, Object> boundary(UUID transactionId, String state, String eventType,
                                        Map<String, Object> request, Map<String, Object> extraPayload) {
        String actor = required(request, "actor");
        String reason = required(request, "reason");
        var existing = repository.existingEvent(transactionId, eventType);
        if (existing.isPresent()) {
            java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("transactionId", transactionId);
            repository.latestState(transactionId, state).ifPresent(stateId -> response.put("stateId", stateId));
            response.put("eventId", existing.get().eventId());
            response.put("eventType", eventType);
            response.put("state", state);
            response.put("duplicateSafe", true);
            return response;
        }
        UUID stateId = repository.state(transactionId, state, reason, actor, Map.of("boundaryOnly", true));
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>(extraPayload);
        payload.put("stateId", stateId);
        payload.put("transactionId", transactionId);
        UUID eventId = repository.event(transactionId, eventType, reason, actor, payload);
        outboxRepository.insert("PAYMENT_BOUNDARY", eventId, null, "PAYMENT_BOUNDARY_STATE_CHANGED", Map.of("state", state));
        outboxRepository.insert("PAYMENT_BOUNDARY", eventId, null, eventType, Map.of("transactionId", transactionId, "state", state));
        return Map.of("transactionId", transactionId, "stateId", stateId, "eventId", eventId, "eventType", eventType, "state", state);
    }

    private String required(Map<String, Object> request, String field) {
        Object value = request.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.toString();
    }
}
