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
        results.add(result(tx, "transaction_listing_exists",
                repository.listingExists(tx.listingId()),
                "Transaction listing must exist"));
        results.add(result(tx, "status_valid_for_type",
                validStatusForType(tx.transactionType(), tx.status()),
                "Transaction status must be valid for its type"));
        results.add(result(tx, "single_accept_has_one_active_transaction",
                repository.activeTransactionsForSingleAcceptListing(tx.listingId()) <= 1,
                "Single-accept listings can have only one active transaction"));
        results.add(result(tx, "active_listing_state_allowed",
                listingStateAllowed(tx),
                "Active transactions cannot start from hidden, rejected, or expired listings"));
        results.add(result(tx, "risk_snapshot_exists",
                repository.riskSnapshotCount(transactionId) > 0,
                "Created transactions should have a risk snapshot"));
        results.add(result(tx, "created_or_accepted_event_exists",
                repository.marketplaceEventCount(transactionId, "TRANSACTION_CREATED") > 0
                        || repository.marketplaceEventCount(transactionId, "TRANSACTION_ACCEPTED") > 0,
                "Created transactions should have a transaction outbox event"));
        return results;
    }

    private boolean listingStateAllowed(TransactionResponse tx) {
        if (tx.status() == TransactionStatus.COMPLETED
                || tx.status() == TransactionStatus.CANCELLED
                || tx.status() == TransactionStatus.DISPUTED
                || tx.status() == TransactionStatus.NO_SHOW_REPORTED) {
            return true;
        }
        return repository.listingState(tx.listingId())
                .map(state -> !List.of("HIDDEN", "REJECTED", "EXPIRED").contains(state.status()))
                .orElse(false);
    }

    private boolean validStatusForType(TransactionType type, TransactionStatus status) {
        return switch (type) {
            case SERVICE_BOOKING -> List.of(
                    TransactionStatus.REQUESTED, TransactionStatus.ACCEPTED, TransactionStatus.SCHEDULED,
                    TransactionStatus.IN_PROGRESS, TransactionStatus.COMPLETION_CLAIMED,
                    TransactionStatus.BUYER_CONFIRMED, TransactionStatus.COMPLETED,
                    TransactionStatus.CANCELLED, TransactionStatus.NO_SHOW_REPORTED, TransactionStatus.DISPUTED
            ).contains(status);
            case ITEM_PURCHASE -> List.of(
                    TransactionStatus.PURCHASED, TransactionStatus.SHIPPED, TransactionStatus.DELIVERED,
                    TransactionStatus.CONFIRMATION_WINDOW_OPEN, TransactionStatus.COMPLETED,
                    TransactionStatus.RETURN_REQUESTED, TransactionStatus.DISPUTED, TransactionStatus.CANCELLED
            ).contains(status);
            case ERRAND -> List.of(
                    TransactionStatus.POSTED, TransactionStatus.ACCEPTED, TransactionStatus.IN_PROGRESS,
                    TransactionStatus.PROOF_UPLOADED, TransactionStatus.REQUESTER_CONFIRMED,
                    TransactionStatus.COMPLETED, TransactionStatus.DISPUTED, TransactionStatus.CANCELLED,
                    TransactionStatus.NO_SHOW_REPORTED
            ).contains(status);
            case SHOPPING_REQUEST -> List.of(
                    TransactionStatus.POSTED, TransactionStatus.ACCEPTED_BY_SHOPPER,
                    TransactionStatus.PURCHASE_PROOF_UPLOADED, TransactionStatus.DELIVERY_PROOF_UPLOADED,
                    TransactionStatus.BUYER_CONFIRMED, TransactionStatus.COMPLETED,
                    TransactionStatus.DISPUTED, TransactionStatus.CANCELLED
            ).contains(status);
        };
    }

    private TransactionInvariantResult result(TransactionResponse tx, String name, boolean pass, String message) {
        return new TransactionInvariantResult(tx.transactionId(), name, pass ? "PASS" : "FAIL", message);
    }
}
