package com.docsearch.service;
import com.docsearch.model.EsSearchResult;
import com.docsearch.dto.SearchQueryParams;
import com.docsearch.model.EsDocument;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HighlighterType;
import com.docsearch.config.AppProperties;
import com.docsearch.dto.FacetBucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the Elasticsearch Java client for exactly the
 * operations this service needs: index, delete, and a single search query
 * that combines relevance ranking, fuzzy matching, highlighting and
 * tag faceting. Every call is routed on tenantId so a query only ever
 * touches the shard(s) holding that tenant's data.
 */
@Component
@Slf4j
public class ElasticsearchDocumentIndex {

    private static final int FACET_SIZE = 20;

    private final ElasticsearchClient client;
    private final String indexName;

    public ElasticsearchDocumentIndex(ElasticsearchClient client, AppProperties properties) {
        this.client = client;
        this.indexName = properties.elasticsearch().indexName();
    }

    public void index(EsDocument document) throws IOException {
        client.index(i -> i
                .index(indexName)
                .id(document.id())
                .routing(document.tenantId())
                .document(document));
    }

    public void delete(String tenantId, String documentId) throws IOException {
        client.delete(d -> d
                .index(indexName)
                .id(documentId)
                .routing(tenantId));
    }

    public EsSearchResult search(String tenantId, SearchQueryParams params) throws IOException {
        Query filter = Query.of(q -> q.term(t -> t.field("tenantId").value(tenantId)));

        List<Query> mustFilters = new ArrayList<>();
        mustFilters.add(filter);
        if (params.tagsFilter() != null && !params.tagsFilter().isEmpty()) {
            List<FieldValue> values = params.tagsFilter().stream().map(FieldValue::of).toList();
            mustFilters.add(Query.of(q -> q.terms(t -> t.field("tags").terms(tf -> tf.value(values)))));
        }
        addTermFilterIfPresent(mustFilters, "department", params.department());
        addTermFilterIfPresent(mustFilters, "category", params.category());
        addTermFilterIfPresent(mustFilters, "docType", params.docType());

        boolean hasQueryText = params.query() != null && !params.query().isBlank();

        Query fullQuery = Query.of(q -> q.bool(b -> {
            b.filter(mustFilters);
            if (hasQueryText) {
                b.must(m -> m.multiMatch(mm -> mm
                        .query(params.query())
                        .fields("title^2", "content")
                        .fuzziness(params.fuzzy() ? "AUTO" : null)));
            }
            return b;
        }));

        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .index(indexName)
                .routing(tenantId)
                .query(fullQuery)
                .from(params.from())
                .size(params.size())
                .highlight(h -> h
                        .type(HighlighterType.Unified)
                        .fields("title", f -> f)
                        .fields("content", f -> f.fragmentSize(150).numberOfFragments(2)))
                .aggregations("tags_facet", a -> a.terms(t -> t.field("tags").size(FACET_SIZE)))
                .aggregations("department_facet", a -> a.terms(t -> t.field("department").size(FACET_SIZE)))
                .aggregations("category_facet", a -> a.terms(t -> t.field("category").size(FACET_SIZE)))
                .aggregations("docType_facet", a -> a.terms(t -> t.field("docType").size(FACET_SIZE)));

        if (!hasQueryText) {
            requestBuilder.sort(s -> s.field(f -> f.field("createdAt").order(SortOrder.Desc)));
        }

        SearchResponse<EsDocument> response = client.search(requestBuilder.build(), EsDocument.class);

        List<Hit<EsDocument>> hits = response.hits().hits();
        long total = response.hits().total() != null ? response.hits().total().value() : hits.size();

        Map<String, List<FacetBucket>> facets = new LinkedHashMap<>();
        extractFacet(response, "tags_facet", "tags", facets);
        extractFacet(response, "department_facet", "department", facets);
        extractFacet(response, "category_facet", "category", facets);
        extractFacet(response, "docType_facet", "docType", facets);

        return new EsSearchResult(total, response.took(), hits, facets);
    }

    private static void addTermFilterIfPresent(List<Query> filters, String field, String value) {
        if (value != null && !value.isBlank()) {
            filters.add(Query.of(q -> q.term(t -> t.field(field).value(value))));
        }
    }

    private static void extractFacet(SearchResponse<EsDocument> response, String aggregationName, String facetKey,
                                      Map<String, List<FacetBucket>> facets) {
        if (response.aggregations() != null && response.aggregations().containsKey(aggregationName)) {
            List<FacetBucket> buckets = response.aggregations().get(aggregationName).sterms().buckets().array()
                    .stream()
                    .map(b -> new FacetBucket(b.key().stringValue(), b.docCount()))
                    .toList();
            facets.put(facetKey, buckets);
        }
    }
}
