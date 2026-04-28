package com.trustgrid.api.risk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record SyntheticRiskSignalRequest(
        @NotNull String signalType,
        @NotBlank String signalHash,
        int riskWeight,
        @NotBlank String source,
        Instant retentionUntil,
        @NotBlank String reason
) {
}
