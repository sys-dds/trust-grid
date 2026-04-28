package com.trustgrid.api.ranking;

import com.trustgrid.api.shared.OutboxRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrustAwareRankingService {

    private final RankingDecisionLogRepository repository;
    private final OutboxRepository outboxRepository;

    public TrustAwareRankingService(RankingDecisionLogRepository repository, OutboxRepository outboxRepository) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    public RankingSearchResponse search(String query, String listingType, String categoryCode, String locationMode,
                                        Long minPriceCents, Long maxPriceCents, RankingPolicyVersion policyVersion,
                                        Integer limit, Integer offset) {
        RankingPolicyVersion policy = policyVersion == null ? RankingPolicyVersion.trust_balanced_v1 : policyVersion;
        int resolvedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        int resolvedOffset = Math.max(offset == null ? 0 : offset, 0);
        List<RankingDecisionLogRepository.RankingCandidate> candidates = repository.candidates(query, listingType,
                categoryCode, locationMode, minPriceCents, maxPriceCents, 100, 0);
        List<RankingListingResponse> results = candidates.stream()
                .map(candidate -> score(candidate, query, policy))
                .sorted(Comparator.comparingLong(RankingListingResponse::score).reversed()
                        .thenComparing(result -> result.listingId().toString()))
                .skip(resolvedOffset)
                .limit(resolvedLimit)
                .toList();
        Map<String, Object> filters = Map.of(
                "listingType", listingType == null ? "" : listingType,
                "categoryCode", categoryCode == null ? "" : categoryCode,
                "locationMode", locationMode == null ? "" : locationMode,
                "minPriceCents", minPriceCents == null ? 0 : minPriceCents,
                "maxPriceCents", maxPriceCents == null ? 0 : maxPriceCents
        );
        UUID logId = repository.insertLog(query, filters, policy, candidates, results);
        outboxRepository.insert("RANKING", logId, null, "RANKING_DECISION_LOGGED",
                Map.of("policyVersion", policy.name(), "resultCount", results.size()));
        return new RankingSearchResponse(logId, policy, results, resolvedLimit, resolvedOffset);
    }

    @Transactional
    public RankingReplayResponse replay(UUID rankingDecisionId) {
        RankingReplayResponse response = repository.replay(rankingDecisionId);
        outboxRepository.insert("RANKING", rankingDecisionId, null, "RANKING_REPLAYED",
                Map.of("matched", response.matched(), "policyVersion", response.policyVersion().name()));
        return response;
    }

    private RankingListingResponse score(RankingDecisionLogRepository.RankingCandidate candidate, String query,
                                         RankingPolicyVersion policy) {
        long score = 0;
        List<String> reasons = new ArrayList<>();
        if (query != null && !query.isBlank() && candidate.title().toLowerCase().contains(query.toLowerCase())) {
            score += 25;
            reasons.add("text_match:+25");
        }
        score += 15;
        reasons.add("category_match:+15");
        score += Math.min(30, candidate.trustScore() / 35);
        reasons.add("trust_score:+" + Math.min(30, candidate.trustScore() / 35));
        score += Math.min(20, candidate.trustConfidence() / 5);
        reasons.add("confidence:+" + Math.min(20, candidate.trustConfidence() / 5));
        if ("VERIFIED".equals(candidate.verificationStatus()) || "ENHANCED".equals(candidate.verificationStatus())) {
            score += 12;
            reasons.add("verification_boost:+12");
        }
        if ("NEW".equals(candidate.trustTier()) || "LIMITED".equals(candidate.trustTier())) {
            int exploration = policy == RankingPolicyVersion.new_user_fairness_v1 ? 10 : 2;
            score += exploration;
            reasons.add("new_user_exploration:+" + exploration);
        }
        if (policy == RankingPolicyVersion.risk_averse_v1 && ("NEW".equals(candidate.trustTier()) || "LIMITED".equals(candidate.trustTier()))) {
            score -= 10;
            reasons.add("risk_penalty:-10");
        }
        if (policy == RankingPolicyVersion.trust_balanced_v1) {
            score += 5;
            reasons.add("balanced_policy:+5");
        }
        return new RankingListingResponse(candidate.listingId(), candidate.ownerParticipantId(), candidate.listingType(),
                candidate.categoryCode(), candidate.title(), candidate.locationMode(), score, reasons);
    }
}
