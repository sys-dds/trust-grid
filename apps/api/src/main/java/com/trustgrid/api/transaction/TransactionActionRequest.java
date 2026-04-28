package com.trustgrid.api.transaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TransactionActionRequest(
        @NotNull UUID actorParticipantId,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
