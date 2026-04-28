package com.trustgrid.api.search;

import java.util.UUID;

public record ListingSearchDocument(UUID listingId, boolean searchable, String searchBackendStatus) {
}
