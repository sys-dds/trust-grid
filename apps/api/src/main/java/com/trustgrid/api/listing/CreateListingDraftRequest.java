package com.trustgrid.api.listing;

import com.trustgrid.api.category.ListingType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record CreateListingDraftRequest(
        @NotNull UUID ownerParticipantId,
        @NotNull ListingType listingType,
        @NotBlank String categoryCode,
        @NotBlank String title,
        @NotBlank String description,
        Long priceAmountCents,
        Long budgetAmountCents,
        String currency,
        @NotNull LocationMode locationMode,
        Boolean singleAccept,
        @NotBlank String createdBy,
        @NotBlank String reason,
        Map<String, Object> details
) {
}
