package com.trustgrid.api.transaction;

public enum TransactionRiskStatus {
    NOT_CHECKED,
    ALLOWED,
    ALLOWED_WITH_LIMITS,
    BLOCKED,
    REQUIRES_REVIEW
}
