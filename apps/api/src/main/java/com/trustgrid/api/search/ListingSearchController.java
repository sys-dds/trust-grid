package com.trustgrid.api.search;

import com.trustgrid.api.category.ListingType;
import com.trustgrid.api.listing.ListingRiskTier;
import com.trustgrid.api.listing.ListingSearchResponse;
import com.trustgrid.api.listing.LocationMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/listings/search")
public class ListingSearchController {

    private final ListingSearchService service;
    private final ListingIndexService indexService;

    public ListingSearchController(ListingSearchService service, ListingIndexService indexService) {
        this.service = service;
        this.indexService = indexService;
    }

    @GetMapping
    public ListingSearchResponse search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) ListingType listingType,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) LocationMode locationMode,
            @RequestParam(required = false) Long minPriceCents,
            @RequestParam(required = false) Long maxPriceCents,
            @RequestParam(required = false) ListingRiskTier riskTier,
            @RequestParam(required = false) Boolean trustedOnly,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset
    ) {
        int resolvedLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        int resolvedOffset = Math.max(offset == null ? 0 : offset, 0);
        return new ListingSearchResponse(
                indexService.currentBackendStatus(),
                service.search(query, listingType, categoryCode, locationMode, minPriceCents, maxPriceCents, riskTier, trustedOnly, resolvedLimit, resolvedOffset),
                resolvedLimit,
                resolvedOffset
        );
    }
}
