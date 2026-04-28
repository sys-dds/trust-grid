package com.trustgrid.api.transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class TransactionInvariantVerifier {

    private final TransactionRepository repository;

    public TransactionInvariantVerifier(TransactionRepository repository) {
        this.repository = repository;
    }

    public List<TransactionInvariantResult> verify(UUID transactionId) {
        if (transactionId == null) {
            return List.of(new TransactionInvariantResult(null, "request_scope", "PASS", "Verifier ran without a specific transaction"));
        }
        TransactionResponse tx = repository.find(transactionId).orElseThrow();
        List<TransactionInvariantResult> results = new ArrayList<>();
        boolean terminal = tx.status() == TransactionStatus.COMPLETED || tx.status() == TransactionStatus.CANCELLED || tx.status() == TransactionStatus.DISPUTED;
        results.add(result(tx, "terminal_status_has_no_active_deadlines",
                !terminal || repository.activeDeadlineCount(transactionId) == 0,
                "Terminal transactions must not have active deadlines"));
        results.add(result(tx, "completed_has_completion_event",
                tx.status() != TransactionStatus.COMPLETED || repository.timelineCount(transactionId, "TRANSACTION_COMPLETED") > 0,
                "Completed transactions must have completion event"));
        results.add(result(tx, "cancelled_cannot_be_completed",
                !(tx.status() == TransactionStatus.CANCELLED && tx.completedAt() != null),
                "Cancelled transactions cannot be completed"));
        results.add(result(tx, "accepted_has_provider",
                tx.providerParticipantId() != null,
                "Accepted transactions must have a provider or shopper"));
        results.add(result(tx, "participants_different",
                !tx.requesterParticipantId().equals(tx.providerParticipantId()),
                "Transaction participants must be different"));
        results.add(result(tx, "positive_value",
                tx.valueAmountCents() > 0,
                "Transaction value must be positive"));
        return results;
    }

    private TransactionInvariantResult result(TransactionResponse tx, String name, boolean pass, String message) {
        return new TransactionInvariantResult(tx.transactionId(), name, pass ? "PASS" : "FAIL", message);
    }
}
