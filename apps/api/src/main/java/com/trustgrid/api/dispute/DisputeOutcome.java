package com.trustgrid.api.dispute;

public enum DisputeOutcome {
    BUYER_WINS,
    SELLER_WINS,
    PROVIDER_WINS,
    SPLIT_DECISION,
    INSUFFICIENT_EVIDENCE,
    FRAUD_SUSPECTED,
    SAFETY_ESCALATION
}
