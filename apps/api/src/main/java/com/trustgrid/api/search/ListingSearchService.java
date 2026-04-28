package com.trustgrid.api.search;

import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.listing.ListingResponse;
import com.trustgrid.api.listing.ListingRiskTier;
import com.trustgrid.api.listing.LocationMode;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ListingSearchService {

    private final PostgresListingSearchFallbackRepository repository;

    public ListingSearchService(PostgresListingSearchFallbackRepository repository) {
        this.repository = repository;
    }

    public List<ListingResponse> search(String query, ListingType listingType, String categoryCode, LocationMode locationMode,
                                        Long minPriceCents, Long maxPriceCents, ListingRiskTier riskTier,
                                        Boolean trustedOnly, int limit, int offset) {
        return repository.search(query, listingType, categoryCode, locationMode, minPriceCents, maxPriceCents, riskTier, trustedOnly, limit, offset);
    }
}
