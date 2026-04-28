package com.trustgrid.api.reputation;

import com.trustgrid.api.shared.NotFoundException;
import com.trustgrid.api.shared.OutboxRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReputationService {

    private final ReputationRepository repository;
    private final TrustTierTransitionService transitionService;
    private final OutboxRepository outboxRepository;

    public ReputationService(ReputationRepository repository, TrustTierTransitionService transitionService,
                             OutboxRepository outboxRepository) {
        this.repository = repository;
        this.transitionService = transitionService;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public ReputationSnapshotResponse recalculate(UUID participantId, String reason) {
        ReputationRepository.ParticipantReputationView participant = repository.participant(participantId)
                .orElseThrow(() -> new NotFoundException("Participant not found"));
        ReputationRepository.ReviewStats reviewStats = repository.reviewStats(participantId);
        int completed = repository.completedTransactions(participantId);
        int cancelled = repository.negativeTransactionSignals(participantId, "CANCELLED");
        int noShows = repository.negativeTransactionSignals(participantId, "NO_SHOW_REPORTED");
        int disputes = repository.disputeCount(participantId);
        int profileQuality = repository.profileQuality(participantId);

        int reviewScore = reviewStats.reviewCount() == 0 ? 0 : (int) Math.round(reviewStats.averageRating() * 100);
        int completionRate = completed == 0 ? 0 : Math.min(100, completed * 25);
        int cancellationPenalty = cancelled * 25;
        int noShowPenalty = noShows * 40;
        int disputePenalty = disputes * 35;
        int evidenceReliability = Math.max(0, 70 - disputePenalty);
        int score = Math.max(0, Math.min(1000, 500 + reviewScore + completionRate + profileQuality
                + evidenceReliability - cancellationPenalty - noShowPenalty - disputePenalty));
        int confidence = Math.max(0, Math.min(100, reviewStats.reviewCount() * 20 + completed * 10
                + ("VERIFIED".equals(participant.verificationStatus()) || "ENHANCED".equals(participant.verificationStatus()) ? 15 : 0)));
        String tier = transitionService.tierFor(participant.accountStatus(), score, confidence, disputes >= 3);
        String riskLevel = disputes >= 3 || noShows > 0 ? "HIGH" : cancelled > 0 ? "MEDIUM" : "LOW";
        List<String> strengths = new ArrayList<>();
        if (reviewScore > 0) {
            strengths.add("review_score");
        }
        if (profileQuality > 0) {
            strengths.add("profile_quality");
        }
        List<String> penalties = new ArrayList<>();
        if (cancelled > 0) {
            penalties.add("cancellation_penalty");
        }
        if (noShows > 0) {
            penalties.add("no_show_penalty");
        }
        if (disputes > 0) {
            penalties.add("dispute_penalty");
        }
        Map<String, Object> signals = Map.of(
                "reviewCount", reviewStats.reviewCount(),
                "averageWeight", reviewStats.averageWeight(),
                "completedTransactions", completed,
                "disputes", disputes
        );
        ReputationSnapshotResponse response = repository.insertSnapshot(participantId, score, confidence, tier, riskLevel,
                reviewScore, completionRate, cancellationPenalty, noShowPenalty, disputePenalty, evidenceReliability,
                profileQuality, strengths, penalties, signals);
        repository.insertRecalculation(participantId, participant.trustScore(), score, participant.trustTier(), tier, reason, signals);
        outboxRepository.insert("PARTICIPANT", participantId, participantId, "REPUTATION_RECALCULATED",
                Map.of("previousScore", participant.trustScore(), "newScore", score, "reason", reason));
        if (!participant.trustTier().equals(tier)) {
            outboxRepository.insert("PARTICIPANT", participantId, participantId, "TRUST_TIER_CHANGED",
                    Map.of("previousTier", participant.trustTier(), "newTier", tier));
        }
        return response;
    }

    public ReputationSnapshotResponse get(UUID participantId) {
        return recalculate(participantId, "Snapshot requested");
    }
}
