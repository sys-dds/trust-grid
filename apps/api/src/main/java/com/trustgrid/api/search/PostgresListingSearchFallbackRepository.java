package com.trustgrid.api.search;

import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.listing.ListingResponse;
import com.trustgrid.api.listing.ListingRiskTier;
import com.trustgrid.api.listing.ListingService;
import com.trustgrid.api.listing.LocationMode;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresListingSearchFallbackRepository {

    private final ListingService listingService;

    public PostgresListingSearchFallbackRepository(ListingService listingService) {
        this.listingService = listingService;
    }

    public List<ListingResponse> search(String query, ListingType listingType, String categoryCode, LocationMode locationMode,
                                        Long minPriceCents, Long maxPriceCents, ListingRiskTier riskTier,
                                        Boolean trustedOnly, int limit, int offset) {
        return listingService.search(query, listingType, categoryCode, locationMode, minPriceCents, maxPriceCents, riskTier, trustedOnly, limit, offset);
    }
}
