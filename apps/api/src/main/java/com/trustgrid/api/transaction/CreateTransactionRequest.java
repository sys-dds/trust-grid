package com.trustgrid.api.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record CreateTransactionRequest(
        @NotNull UUID requesterParticipantId,
        UUID providerParticipantId,
        @NotBlank String actor,
        @NotBlank String reason,
        Map<String, Object> metadata
) {
}
