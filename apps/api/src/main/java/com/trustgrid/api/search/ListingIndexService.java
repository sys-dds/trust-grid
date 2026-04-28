package com.trustgrid.api.search;

import org.springframework.stereotype.Service;

@Service
public class ListingIndexService {

    private final OpenSearchListingIndexer indexer;

    public ListingIndexService(OpenSearchListingIndexer indexer) {
        this.indexer = indexer;
    }

    public String currentBackendStatus() {
        return indexer.backendStatus();
    }
}
