package com.trustgrid.api.reputation;

import org.springframework.stereotype.Service;

@Service
public class TrustTierTransitionService {

    public String tierFor(String accountStatus, int score, int confidence, boolean seriousRestriction) {
        if ("SUSPENDED".equals(accountStatus) || "CLOSED".equals(accountStatus)) {
            return "SUSPENDED";
        }
        if (seriousRestriction || "RESTRICTED".equals(accountStatus)) {
            return "RESTRICTED";
        }
        if (score >= 850 && confidence >= 70) {
            return "HIGH_TRUST";
        }
        if (score >= 750 && confidence >= 60) {
            return "TRUSTED";
        }
        if (score >= 600 && confidence >= 40) {
            return "STANDARD";
        }
        if (score >= 450) {
            return "LIMITED";
        }
        return "NEW";
    }
}
