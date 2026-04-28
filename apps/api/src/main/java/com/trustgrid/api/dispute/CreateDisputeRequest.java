package com.trustgrid.api.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record CreateDisputeRequest(
        @NotNull UUID openedByParticipantId,
        @NotNull DisputeType disputeType,
        @NotBlank String reason,
        Map<String, Object> metadata
) {
}
