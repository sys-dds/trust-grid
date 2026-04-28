package com.trustgrid.api.paymentboundary;

import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentBoundaryService {
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
        return boundary(transactionId, "RELEASE_REQUESTED", "MARKETPLACE_FUNDS_RELEASE_REQUESTED", request);
    }

    @Transactional
    public Map<String, Object> refund(UUID transactionId, Map<String, Object> request) {
        if (!repository.resolvedDisputeExists(transactionId)) {
            throw new ConflictException("Resolved dispute required for refund request");
        }
        return boundary(transactionId, "REFUND_REQUESTED", "MARKETPLACE_REFUND_REQUESTED", request);
    }

    @Transactional
    public Map<String, Object> hold(UUID transactionId, Map<String, Object> request) {
        return boundary(transactionId, "PAYOUT_HOLD_REQUESTED", "MARKETPLACE_PAYOUT_HOLD_REQUESTED", request);
    }

    @Transactional
    public Map<String, Object> close(UUID transactionId, Map<String, Object> request) {
        return boundary(transactionId, "TRANSACTION_CLOSED", "MARKETPLACE_TRANSACTION_CLOSED", request);
    }

    private Map<String, Object> boundary(UUID transactionId, String state, String eventType, Map<String, Object> request) {
        String actor = required(request, "actor");
        String reason = required(request, "reason");
        UUID stateId = repository.state(transactionId, state, reason, actor, Map.of("boundaryOnly", true));
        UUID eventId = repository.event(transactionId, eventType, reason, actor, Map.of("stateId", stateId, "transactionId", transactionId));
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
