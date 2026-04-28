package com.trustgrid.api.dispute;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record DisputeStatementRequest(
        UUID participantId,
        @NotNull StatementType statementType,
        @NotBlank String statementText,
        @NotBlank String actor,
        @NotBlank String reason,
        Map<String, Object> metadata
) {
}
