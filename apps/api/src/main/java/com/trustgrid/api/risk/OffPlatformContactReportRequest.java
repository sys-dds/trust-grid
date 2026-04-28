package com.trustgrid.api.risk;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OffPlatformContactReportRequest(
        @NotNull UUID reporterParticipantId,
        @NotNull UUID reportedParticipantId,
        @NotBlank String reportText,
        @NotBlank String reason
) {
}
