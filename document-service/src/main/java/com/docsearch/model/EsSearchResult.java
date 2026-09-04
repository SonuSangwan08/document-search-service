package com.docsearch.model;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.docsearch.dto.FacetBucket;

import java.util.List;
import java.util.Map;

public record EsSearchResult(
        long totalHits,
        long tookMs,
        List<Hit<EsDocument>> hits,
        Map<String, List<FacetBucket>> facets
) {
}
