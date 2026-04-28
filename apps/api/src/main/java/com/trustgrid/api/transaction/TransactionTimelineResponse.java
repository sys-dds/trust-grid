package com.trustgrid.api.transaction;

import java.util.List;

public record TransactionTimelineResponse(List<TransactionTimelineEventResponse> events, int limit, int offset) {
}
