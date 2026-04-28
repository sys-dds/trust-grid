package com.trustgrid.api.review;

import com.trustgrid.api.idempotency.IdempotencyService;
import com.trustgrid.api.reputation.ReputationService;
import com.trustgrid.api.shared.ConflictException;
import com.trustgrid.api.shared.MarketplaceActionForbiddenException;
import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewService {

    private final ReviewRepository repository;
    private final ReviewConfidenceWeightingService weightingService;
    private final IdempotencyService idempotencyService;
    private final ReputationService reputationService;
    private final OutboxRepository outboxRepository;

    public ReviewService(ReviewRepository repository, ReviewConfidenceWeightingService weightingService,
                         IdempotencyService idempotencyService, ReputationService reputationService,
                         OutboxRepository outboxRepository) {
        this.repository = repository;
        this.weightingService = weightingService;
        this.idempotencyService = idempotencyService;
        this.reputationService = reputationService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public ReviewResponse create(UUID transactionId, String idempotencyKey, CreateReviewRequest request) {
        return idempotencyService.run("review:create:" + transactionId + ":" + request.reviewerParticipantId(),
                idempotencyKey, Map.of("transactionId", transactionId), request, "REVIEW", this::get, () -> {
                    ReviewRepository.TransactionReviewView tx = repository.transaction(transactionId)
                            .orElseThrow(() -> new NotFoundException("Transaction not found"));
                    if (!"COMPLETED".equals(tx.status())) {
                        throw new ConflictException("Only completed transactions are reviewable");
                    }
                    if (repository.hasUnresolvedDispute(transactionId)) {
                        throw new ConflictException("Unresolved dispute blocks review");
                    }
                    boolean reviewerInTransaction = request.reviewerParticipantId().equals(tx.requesterParticipantId())
                            || request.reviewerParticipantId().equals(tx.providerParticipantId());
                    boolean reviewedInTransaction = request.reviewedParticipantId().equals(tx.requesterParticipantId())
                            || request.reviewedParticipantId().equals(tx.providerParticipantId());
                    if (!reviewerInTransaction || !reviewedInTransaction || request.reviewerParticipantId().equals(request.reviewedParticipantId())) {
                        throw new MarketplaceActionForbiddenException("Review participants must be opposite transaction sides");
                    }
                    if (repository.reviewExists(transactionId, request.reviewerParticipantId(), request.reviewedParticipantId())) {
                        throw new ConflictException("Review already exists for this side");
                    }
                    String tier = repository.participantTier(request.reviewerParticipantId());
                    int weight = weightingService.weight(tier, tx.valueAmountCents(), false,
                            repository.repeatPairCount(request.reviewerParticipantId(), request.reviewedParticipantId()));
                    UUID reviewId = repository.insert(transactionId, request, weight);
                    outboxRepository.insert("REVIEW", reviewId, request.reviewerParticipantId(), "REVIEW_CREATED",
                            Map.of("transactionId", transactionId, "reviewedParticipantId", request.reviewedParticipantId()));
                    reputationService.recalculate(request.reviewedParticipantId(), "Review created");
                    return reviewId;
                });
    }

    public ReviewResponse get(UUID reviewId) {
        return repository.find(reviewId).orElseThrow(() -> new NotFoundException("Review not found"));
    }

    public List<ReviewResponse> participantReviews(UUID participantId) {
        return repository.participantReviews(participantId);
    }
}
