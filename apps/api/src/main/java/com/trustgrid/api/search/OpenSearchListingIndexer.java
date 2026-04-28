package com.trustgrid.api.search;

import org.springframework.stereotype.Component;

@Component
public class OpenSearchListingIndexer {

    public String backendStatus() {
        return "POSTGRES_FALLBACK";
    }
}
