package com.trustgrid.api.transaction;

import java.util.List;

public record TransactionInvariantResponse(List<TransactionInvariantResult> results) {
}
