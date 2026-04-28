package com.trustgrid.api.transaction;

import java.util.UUID;

public record TransactionInvariantResult(UUID transactionId, String checkName, String status, String message) {
}
