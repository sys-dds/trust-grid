package com.trustgrid.api.analytics;

public record FraudMetricsResponse(int suspiciousClusters, int offPlatformReports, int blockedTransactions,
                                   int highRiskDecisions, String policyVersion) {
}
