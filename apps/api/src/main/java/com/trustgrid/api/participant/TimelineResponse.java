package com.trustgrid.api.participant;

import java.util.List;

public record TimelineResponse(List<TimelineEventResponse> events, int limit, int offset) {
}
