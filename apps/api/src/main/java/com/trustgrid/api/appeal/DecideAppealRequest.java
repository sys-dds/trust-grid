package com.trustgrid.api.appeal;

import jakarta.validation.constraints.NotBlank;

public record DecideAppealRequest(@NotBlank String decision, @NotBlank String decidedBy, @NotBlank String reason) {
}
