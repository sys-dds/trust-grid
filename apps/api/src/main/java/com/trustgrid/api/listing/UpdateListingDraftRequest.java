package com.trustgrid.api.listing;

import jakarta.validation.constraints.NotBlank;

public record UpdateListingDraftRequest(
        @NotBlank String title,
        @NotBlank String description,
        Long priceAmountCents,
        Long budgetAmountCents,
        LocationMode locationMode,
        @NotBlank String updatedBy,
        @NotBlank String reason
) {
}
