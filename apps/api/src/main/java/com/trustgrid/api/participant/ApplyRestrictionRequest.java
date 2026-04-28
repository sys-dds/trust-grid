package com.trustgrid.api.participant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApplyRestrictionRequest(
        @NotNull RestrictionType restrictionType,
        Long maxTransactionValueCents,
        @NotBlank String actor,
        @NotBlank String reason
) {
}
