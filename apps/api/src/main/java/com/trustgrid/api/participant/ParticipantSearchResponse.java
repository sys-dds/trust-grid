package com.trustgrid.api.participant;

import java.util.List;

public record ParticipantSearchResponse(List<ParticipantResponse> participants, int limit, int offset) {
}
