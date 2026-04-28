package com.trustgrid.api.transaction;

import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.idempotency.IdempotencyService;
import com.trustgrid.api.participant.ParticipantAccountStatus;
import com.trustgrid.api.participant.VerificationStatus;
import com.trustgrid.api.risk.RiskDecision;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.transaction.TransactionRepository.ListingTransactionView;
import com.trustgrid.api.transaction.TransactionRepository.ParticipantTransactionView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository repository;
    private final IdempotencyService idempotencyService;
    private final TransactionInvariantVerifier invariantVerifier;
    private final TransactionActorAuthorizationService actorAuthorizationService;

    public TransactionService(TransactionRepository repository, IdempotencyService idempotencyService,
                              TransactionInvariantVerifier invariantVerifier,
                              TransactionActorAuthorizationService actorAuthorizationService) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.invariantVerifier = invariantVerifier;
        this.actorAuthorizationService = actorAuthorizationService;
    }

    @Transactional
    public TransactionResponse create(UUID listingId, String idempotencyKey, CreateTransactionRequest request) {
        return idempotencyService.run("transaction:create:" + listingId, idempotencyKey, Map.of("listingId", listingId), request,
                "TRANSACTION", this::get, () -> {
                    ListingTransactionView listing = repository.lockListing(listingId).orElseThrow(() -> new NotFoundException("Listing not found"));
                    if (!"LIVE".equals(listing.status())) {
                        throw new MarketplaceActionForbiddenException("Listing is not live");
                    }
                    if (listing.singleAccept() && repository.hasActiveTransaction(listingId)) {
                        throw new ConflictException("Listing already has an active transaction");
                    }
                    TransactionType type = typeFor(listing.listingType());
                    UUID requesterId = requesterFor(listing, request);
                    UUID providerId = providerFor(listing, request);
                    if (requesterId.equals(providerId)) {
                        throw new ConflictException("Transaction participants must be different");
                    }
                    long value = valueFor(listing);
                    List<String> rules = validateRisk(listing, requesterId, providerId, value);
                    UUID transactionId = repository.insertTransaction(listing, type, requesterId, providerId, value,
                            idempotencyKey, request.metadata() == null ? Map.of() : request.metadata(), initialStatus(type));
                    repository.insertRiskSnapshot(transactionId, listingId, requesterId, providerId,
                            rules.isEmpty() ? RiskDecision.ALLOW.name() : RiskDecision.ALLOW_WITH_LIMITS.name(), rules);
                    repository.timeline(transactionId, "TRANSACTION_CREATED", requesterId, request.actor(), request.reason(), Map.of("listingId", listingId));
                    repository.timeline(transactionId, "TRANSACTION_ACCEPTED", providerId, request.actor(), request.reason(), Map.of("transactionType", type.name()));
                    repository.insertEvent(transactionId, requesterId, "TRANSACTION_CREATED", Map.of("listingId", listingId, "transactionType", type.name()));
                    repository.insertEvent(transactionId, providerId, "TRANSACTION_ACCEPTED", Map.of("listingId", listingId, "providerParticipantId", providerId));
                    repository.insertEvent(transactionId, requesterId, "TRANSACTION_RISK_SNAPSHOT_RECORDED", Map.of("matchedRules", rules));
                    createInitialDeadlines(transactionId, type);
                    return transactionId;
                });
    }

    public TransactionResponse get(UUID transactionId) {
        return repository.find(transactionId).orElseThrow(() -> new NotFoundException("Transaction not found"));
    }

    @Transactional
    public TransactionResponse start(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "start", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_STARTED", "started_at", tx -> {
            if (tx.transactionType() != TransactionType.SERVICE_BOOKING && tx.transactionType() != TransactionType.ERRAND) {
                throw new ConflictException("Transaction type cannot be started");
            }
            if (tx.status() != TransactionStatus.ACCEPTED) {
                throw new ConflictException("Transaction must be accepted to start");
            }
            return TransactionStatus.IN_PROGRESS;
        });
    }

    @Transactional
    public TransactionResponse markShipped(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "ship", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_SHIPPED", "shipped_at", tx -> {
            if (tx.transactionType() != TransactionType.ITEM_PURCHASE || tx.status() != TransactionStatus.PURCHASED) {
                throw new ConflictException("Item purchase must be purchased before shipment");
            }
            return TransactionStatus.SHIPPED;
        });
    }

    @Transactional
    public TransactionResponse markDelivered(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "deliver", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_DELIVERED", "delivered_at", tx -> {
            if (tx.transactionType() != TransactionType.ITEM_PURCHASE || tx.status() != TransactionStatus.SHIPPED) {
                throw new ConflictException("Item purchase must be shipped before delivery");
            }
            repository.createDeadline(transactionId, "BUYER_CONFIRMATION_WINDOW", 3);
            repository.insertEvent(transactionId, request.actorParticipantId(), "TRANSACTION_DEADLINE_CREATED", Map.of("deadlineType", "BUYER_CONFIRMATION_WINDOW"));
            return TransactionStatus.CONFIRMATION_WINDOW_OPEN;
        });
    }

    @Transactional
    public TransactionResponse markProof(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "proof", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_PROOF_PLACEHOLDER_RECORDED", "proof_placeholder_at", tx -> switch (tx.transactionType()) {
            case ERRAND -> {
                if (tx.status() != TransactionStatus.IN_PROGRESS) {
                    throw new ConflictException("Errand must be in progress before proof placeholder");
                }
                yield TransactionStatus.PROOF_UPLOADED;
            }
            case SHOPPING_REQUEST -> {
                if (tx.status() != TransactionStatus.ACCEPTED_BY_SHOPPER && tx.status() != TransactionStatus.PURCHASE_PROOF_UPLOADED) {
                    throw new ConflictException("Shopping request is not ready for proof placeholder");
                }
                yield tx.status() == TransactionStatus.ACCEPTED_BY_SHOPPER
                        ? TransactionStatus.PURCHASE_PROOF_UPLOADED
                        : TransactionStatus.DELIVERY_PROOF_UPLOADED;
            }
            default -> throw new ConflictException("Proof placeholder is not valid for this transaction type");
        });
    }

    @Transactional
    public TransactionResponse claimCompletion(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "claim-completion", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_COMPLETION_CLAIMED", "completion_claimed_at", tx -> {
            if (tx.transactionType() == TransactionType.SERVICE_BOOKING && tx.status() == TransactionStatus.IN_PROGRESS) {
                repository.createDeadline(transactionId, "BUYER_CONFIRMATION_WINDOW", 3);
                repository.insertEvent(transactionId, request.actorParticipantId(), "TRANSACTION_DEADLINE_CREATED", Map.of("deadlineType", "BUYER_CONFIRMATION_WINDOW"));
                return TransactionStatus.COMPLETION_CLAIMED;
            }
            throw new ConflictException("Transaction cannot claim completion from current status");
        });
    }

    @Transactional
    public TransactionResponse confirmCompletion(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "confirm-completion", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_CONFIRMED", "confirmed_at", tx -> {
            boolean valid = switch (tx.transactionType()) {
                case SERVICE_BOOKING -> tx.status() == TransactionStatus.COMPLETION_CLAIMED;
                case ITEM_PURCHASE -> tx.status() == TransactionStatus.CONFIRMATION_WINDOW_OPEN;
                case ERRAND -> tx.status() == TransactionStatus.PROOF_UPLOADED;
                case SHOPPING_REQUEST -> tx.status() == TransactionStatus.DELIVERY_PROOF_UPLOADED;
            };
            if (!valid) {
                throw new ConflictException("Transaction cannot be confirmed from current status");
            }
            repository.satisfyDeadline(transactionId, "BUYER_CONFIRMATION_WINDOW");
            repository.cancelActiveDeadlines(transactionId);
            repository.insertEvent(transactionId, request.actorParticipantId(), "TRANSACTION_DEADLINE_SATISFIED", Map.of("deadlineType", "BUYER_CONFIRMATION_WINDOW"));
            repository.updateStatus(transactionId, TransactionStatus.COMPLETED, "completed_at");
            repository.timeline(transactionId, "TRANSACTION_COMPLETED", request.actorParticipantId(), request.actor(), request.reason(), Map.of("confirmed", true));
            repository.insertEvent(transactionId, request.actorParticipantId(), "TRANSACTION_COMPLETED", Map.of("confirmed", true));
            return TransactionStatus.COMPLETED;
        });
    }

    @Transactional
    public TransactionResponse cancel(UUID transactionId, String idempotencyKey, TransactionActionRequest request) {
        return mutate(transactionId, "cancel", idempotencyKey, request, request.actorParticipantId(), "TRANSACTION_CANCELLED", "cancelled_at", tx -> {
            if (terminal(tx.status()) || tx.status() == TransactionStatus.IN_PROGRESS) {
                throw new ConflictException("Transaction cannot be cancelled from current status");
            }
            repository.cancelActiveDeadlines(transactionId);
            repository.insertEvent(transactionId, request.actorParticipantId(), "TRANSACTION_DEADLINE_CANCELLED", Map.of("reason", request.reason()));
            return TransactionStatus.CANCELLED;
        });
    }

    @Transactional
    public TransactionResponse reportNoShow(UUID transactionId, String idempotencyKey, NoShowRequest request) {
        TransactionActionRequest action = new TransactionActionRequest(request.reportedByParticipantId(), request.actor(), request.reason());
        return mutate(transactionId, "no-show", idempotencyKey, action, request.reportedByParticipantId(), "TRANSACTION_NO_SHOW_REPORTED", "no_show_reported_at", tx -> {
            if (tx.transactionType() != TransactionType.SERVICE_BOOKING && tx.transactionType() != TransactionType.ERRAND) {
                throw new ConflictException("No-show is only valid for service or errand transactions");
            }
            if (tx.status() != TransactionStatus.ACCEPTED && tx.status() != TransactionStatus.IN_PROGRESS) {
                throw new ConflictException("Transaction is not eligible for no-show report");
            }
            repository.cancelActiveDeadlines(transactionId);
            return TransactionStatus.NO_SHOW_REPORTED;
        });
    }

    public TransactionTimelineResponse timeline(UUID transactionId, String eventType, Instant from, Instant to, Integer limit, Integer offset) {
        get(transactionId);
        int resolvedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        int resolvedOffset = Math.max(offset == null ? 0 : offset, 0);
        return new TransactionTimelineResponse(repository.timeline(transactionId, eventType, from, to, resolvedLimit, resolvedOffset), resolvedLimit, resolvedOffset);
    }

    @Transactional
    public TransactionInvariantResponse verify(InvariantVerifyRequest request) {
        List<TransactionInvariantResult> results = invariantVerifier.verify(request.transactionId());
        results.forEach(result -> repository.storeInvariant(result.transactionId(), result.checkName(), result.status(), result.message()));
        if (request.transactionId() != null) {
            repository.insertEvent(request.transactionId(), null, "TRANSACTION_INVARIANT_CHECK_RUN", Map.of("resultCount", results.size()));
        }
        return new TransactionInvariantResponse(results);
    }

    private TransactionResponse mutate(UUID transactionId, String action, String idempotencyKey, Object request,
                                       UUID actorParticipantId, String eventType, String timestampColumn, StatusMutation mutation) {
        return idempotencyService.run("transaction:" + action + ":" + transactionId, idempotencyKey, Map.of("transactionId", transactionId), request,
                "TRANSACTION", this::get, () -> {
                    TransactionResponse tx = get(transactionId);
                    actorAuthorizationService.authorize(action, tx, actorParticipantId);
                    TransactionStatus next = mutation.next(tx);
                    if (terminal(tx.status())) {
                        throw new ConflictException("Terminal transaction cannot be mutated");
                    }
                    if (next != TransactionStatus.COMPLETED) {
                        repository.updateStatus(transactionId, next, timestampColumn);
                    }
                    String actor = actor(request);
                    String reason = reason(request);
                    repository.timeline(transactionId, eventType, actorParticipantId, actor, reason, Map.of("from", tx.status().name(), "to", next.name()));
                    repository.insertEvent(transactionId, actorParticipantId, eventType, Map.of("from", tx.status().name(), "to", next.name()));
                    return transactionId;
                });
    }

    private List<String> validateRisk(ListingTransactionView listing, UUID requesterId, UUID providerId, long value) {
        List<String> rules = new ArrayList<>();
        ParticipantTransactionView requester = participantOrThrow(requesterId);
        ParticipantTransactionView provider = participantOrThrow(providerId);
        validateParticipant(requester, value, rules);
        validateParticipant(provider, value, rules);
        requiredCapability(listing.listingType(), requesterId, providerId, listing.ownerParticipantId());
        if (listing.riskTier() == com.trustgrid.api.listing.ListingRiskTier.HIGH
                && requester.verificationStatus() == VerificationStatus.UNVERIFIED) {
            throw new MarketplaceActionForbiddenException("High-risk listing requires requester verification");
        }
        return rules;
    }

    private void validateParticipant(ParticipantTransactionView participant, long value, List<String> rules) {
        if (participant.accountStatus() == ParticipantAccountStatus.SUSPENDED) {
            throw new MarketplaceActionForbiddenException("Suspended participant cannot transact");
        }
        if (participant.accountStatus() == ParticipantAccountStatus.CLOSED) {
            throw new MarketplaceActionForbiddenException("Closed participant cannot transact");
        }
        if (repository.hasActiveRestriction(participant.participantId(), "ACCEPTING_BLOCKED")) {
            throw new MarketplaceActionForbiddenException("Participant is blocked from accepting marketplace work");
        }
        if (repository.hasActiveRestriction(participant.participantId(), "REQUIRES_VERIFICATION")
                && participant.verificationStatus() == VerificationStatus.UNVERIFIED) {
            throw new MarketplaceActionForbiddenException("Participant requires verification");
        }
        if (participant.maxTransactionValueCents() > 0 && value > participant.maxTransactionValueCents()) {
            throw new MarketplaceActionForbiddenException("Transaction value exceeds participant limit");
        }
        if (participant.accountStatus() == ParticipantAccountStatus.LIMITED || participant.accountStatus() == ParticipantAccountStatus.RESTRICTED) {
            rules.add("PARTICIPANT_LIMITED");
        }
    }

    private void requiredCapability(ListingType listingType, UUID requesterId, UUID providerId, UUID ownerId) {
        switch (listingType) {
            case SERVICE_OFFER -> {
                if (!providerId.equals(ownerId) || !repository.hasCapability(requesterId, "BUY") || !repository.hasCapability(providerId, "OFFER_SERVICES")) {
                    throw new MarketplaceActionForbiddenException("Missing capability for service booking");
                }
            }
            case ITEM_LISTING -> {
                if (!providerId.equals(ownerId) || !repository.hasCapability(requesterId, "BUY") || !repository.hasCapability(providerId, "SELL_ITEMS")) {
                    throw new MarketplaceActionForbiddenException("Missing capability for item purchase");
                }
            }
            case ERRAND_REQUEST -> {
                if (!requesterId.equals(ownerId) || !repository.hasCapability(providerId, "ACCEPT_ERRANDS")) {
                    throw new MarketplaceActionForbiddenException("Missing capability for errand acceptance");
                }
            }
            case SHOPPING_REQUEST -> {
                if (!requesterId.equals(ownerId) || !repository.hasCapability(providerId, "ACCEPT_SHOPPING_REQUESTS")) {
                    throw new MarketplaceActionForbiddenException("Missing capability for shopping request acceptance");
                }
            }
        }
    }

    private ParticipantTransactionView participantOrThrow(UUID participantId) {
        return repository.participant(participantId).orElseThrow(() -> new NotFoundException("Participant not found"));
    }

    private TransactionType typeFor(ListingType listingType) {
        return switch (listingType) {
            case SERVICE_OFFER -> TransactionType.SERVICE_BOOKING;
            case ITEM_LISTING -> TransactionType.ITEM_PURCHASE;
            case ERRAND_REQUEST -> TransactionType.ERRAND;
            case SHOPPING_REQUEST -> TransactionType.SHOPPING_REQUEST;
        };
    }

    private TransactionStatus initialStatus(TransactionType type) {
        return switch (type) {
            case SERVICE_BOOKING -> TransactionStatus.ACCEPTED;
            case ITEM_PURCHASE -> TransactionStatus.PURCHASED;
            case ERRAND -> TransactionStatus.ACCEPTED;
            case SHOPPING_REQUEST -> TransactionStatus.ACCEPTED_BY_SHOPPER;
        };
    }

    private UUID requesterFor(ListingTransactionView listing, CreateTransactionRequest request) {
        return switch (listing.listingType()) {
            case SERVICE_OFFER, ITEM_LISTING -> request.requesterParticipantId();
            case ERRAND_REQUEST, SHOPPING_REQUEST -> listing.ownerParticipantId();
        };
    }

    private UUID providerFor(ListingTransactionView listing, CreateTransactionRequest request) {
        return switch (listing.listingType()) {
            case SERVICE_OFFER, ITEM_LISTING -> listing.ownerParticipantId();
            case ERRAND_REQUEST, SHOPPING_REQUEST -> request.providerParticipantId();
        };
    }

    private long valueFor(ListingTransactionView listing) {
        Long value = switch (listing.listingType()) {
            case SERVICE_OFFER, ITEM_LISTING -> listing.priceAmountCents();
            case ERRAND_REQUEST -> listing.budgetAmountCents();
            case SHOPPING_REQUEST -> listing.shoppingValue();
        };
        if (value == null || value <= 0) {
            throw new ConflictException("Transaction value must be positive");
        }
        return value;
    }

    private void createInitialDeadlines(UUID transactionId, TransactionType type) {
        switch (type) {
            case SERVICE_BOOKING -> repository.createDeadline(transactionId, "SERVICE_START_DEADLINE", 1);
            case ITEM_PURCHASE -> repository.createDeadline(transactionId, "DELIVERY_PROOF_DEADLINE", 7);
            case ERRAND -> repository.createDeadline(transactionId, "DELIVERY_PROOF_DEADLINE", 7);
            case SHOPPING_REQUEST -> repository.createDeadline(transactionId, "PURCHASE_PROOF_DEADLINE", 2);
        }
        repository.insertEvent(transactionId, null, "TRANSACTION_DEADLINE_CREATED", Map.of("transactionType", type.name()));
    }

    private boolean terminal(TransactionStatus status) {
        return status == TransactionStatus.COMPLETED
                || status == TransactionStatus.CANCELLED
                || status == TransactionStatus.DISPUTED
                || status == TransactionStatus.NO_SHOW_REPORTED;
    }

    private String actor(Object request) {
        if (request instanceof TransactionActionRequest action) {
            return action.actor();
        }
        return ((NoShowRequest) request).actor();
    }

    private String reason(Object request) {
        if (request instanceof TransactionActionRequest action) {
            return action.reason();
        }
        return ((NoShowRequest) request).reason();
    }

    private interface StatusMutation {
        TransactionStatus next(TransactionResponse transaction);
    }
}
