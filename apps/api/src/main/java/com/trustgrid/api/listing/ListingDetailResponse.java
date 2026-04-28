package com.trustgrid.api.listing;

import java.util.Map;

public record ListingDetailResponse(String detailType, Map<String, Object> values) {
}
