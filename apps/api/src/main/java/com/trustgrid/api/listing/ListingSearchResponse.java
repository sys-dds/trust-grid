package com.trustgrid.api.listing;

import java.util.List;

public record ListingSearchResponse(String searchBackend, List<ListingResponse> listings, int limit, int offset) {
}
