package com.trustgrid.api.review;

import org.springframework.stereotype.Service;

@Service
public class ReviewConfidenceWeightingService {

    int weight(String reviewerTier, long valueAmountCents, boolean disputeHistory, int repeatPairCount) {
        int weight = 25;
        if ("TRUSTED".equals(reviewerTier) || "HIGH_TRUST".equals(reviewerTier)) {
            weight += 25;
        } else if ("STANDARD".equals(reviewerTier)) {
            weight += 15;
        }
        if (valueAmountCents >= 25000) {
            weight += 10;
        }
        if (repeatPairCount > 1) {
            weight += 5;
        }
        if (disputeHistory) {
            weight -= 15;
        }
        return Math.max(0, Math.min(100, weight));
    }
}
