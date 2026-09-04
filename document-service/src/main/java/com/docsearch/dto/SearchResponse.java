package com.docsearch.dto;

import java.util.List;
import java.util.Map;

public record SearchResponse(
        String query,
        long totalHits,
        int page,
        int size,
        long tookMs,
        boolean fromCache,
        List<SearchHit> hits,
        Map<String, List<FacetBucket>> facets
) {
}
