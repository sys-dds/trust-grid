package com.trustgrid.api.participant;

public record MarketplaceEligibilityResponse(
        boolean canBuy,
        boolean canSellItems,
        boolean canOfferServices,
        boolean canAcceptErrands,
        boolean canAcceptShoppingRequests,
        boolean canAppearInSearch,
        boolean requiresManualReview,
        boolean requiresVerification
) {
}
