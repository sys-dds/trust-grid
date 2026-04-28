package com.trustgrid.api.risk;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RiskRuleEvaluator {

    RiskDecision decisionFor(List<String> rules) {
        if (rules.stream().anyMatch(rule -> rule.contains("blocked") || rule.contains("above_limit"))) {
            return RiskDecision.BLOCK_TRANSACTION;
        }
        if (rules.stream().anyMatch(rule -> rule.contains("review") || rule.contains("off_platform") || rule.contains("cluster"))) {
            return RiskDecision.REQUIRE_MANUAL_REVIEW;
        }
        if (rules.isEmpty()) {
            return RiskDecision.ALLOW;
        }
        return RiskDecision.ALLOW_WITH_LIMITS;
    }

    String levelFor(List<String> rules) {
        if (rules.stream().anyMatch(rule -> rule.contains("critical") || rule.contains("blocked"))) {
            return "CRITICAL";
        }
        if (rules.stream().anyMatch(rule -> rule.contains("review") || rule.contains("off_platform") || rule.contains("cluster"))) {
            return "HIGH";
        }
        return rules.isEmpty() ? "LOW" : "MEDIUM";
    }

    List<String> nextSteps(RiskDecision decision) {
        return switch (decision) {
            case ALLOW -> List.of("allow_action");
            case ALLOW_WITH_LIMITS -> List.of("allow_with_limit_tracking");
            case REQUIRE_EXTRA_EVIDENCE -> List.of("request_evidence_placeholder");
            case REQUIRE_VERIFICATION -> List.of("request_verification");
            case REQUIRE_MANUAL_REVIEW -> List.of("send_to_manual_review");
            case HIDE_LISTING -> List.of("hide_listing_for_review");
            case BLOCK_TRANSACTION -> List.of("block_transaction");
            case RESTRICT_CAPABILITY -> List.of("recommend_capability_restriction");
            case SUSPEND_ACCOUNT -> List.of("recommend_account_suspension");
            case SUPPRESS_REVIEW_WEIGHT -> List.of("suppress_review_weight");
        };
    }

    List<String> participantRules(int repeatDisputes, int signalWeight) {
        List<String> rules = new ArrayList<>();
        if (repeatDisputes >= 2) {
            rules.add("repeat_dispute_participant_review");
        }
        if (signalWeight >= 20) {
            rules.add("synthetic_cluster_signal_review");
        }
        return rules;
    }
}
