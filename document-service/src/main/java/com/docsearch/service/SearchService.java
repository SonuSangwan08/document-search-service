package com.docsearch.service;
import com.docsearch.model.EsDocument;
import com.docsearch.model.EsSearchResult;
import com.docsearch.dto.SearchQueryParams;

import co.elastic.clients.elasticsearch.core.search.Hit;
import com.docsearch.config.AppProperties;
import com.docsearch.dto.SearchHit;
import com.docsearch.dto.SearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Orchestrates the read path: check the Redis result cache, fall through to
 * Elasticsearch on a miss, cache the result for a short TTL. The TTL is
 * intentionally short (default 30s, see application.yml) - search results
 * are read far more often than documents change, but staleness beyond a few
 * tens of seconds would be a confusing UX for a "just indexed my document"
 * flow. See docs/ARCHITECTURE.md for the full consistency discussion.
 */
@Service
@Slf4j
public class SearchService {

    private final ElasticsearchDocumentIndex searchIndex;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long cacheTtlSeconds;

    public SearchService(ElasticsearchDocumentIndex searchIndex, StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper, AppProperties properties) {
        this.searchIndex = searchIndex;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.cacheTtlSeconds = properties.cache().searchResultsTtlSeconds();
    }

    /**
     * Cache reads/writes are wrapped and never allowed to fail the request -
     * confirmed live (see docs/SUBMISSION.md §2 Resilience) that without
     * this, a Redis outage took search down entirely with a 500, even though
     * Redis here is purely a latency optimization and Elasticsearch is fully
     * capable of serving the request on its own. Fail open: log and query
     * Elasticsearch directly on a cache-read failure, log and skip on a
     * cache-write failure - either way the request still succeeds, just
     * without the caching benefit until Redis recovers.
     */
    public SearchResponse search(String tenantId, SearchQueryParams params) throws IOException {
        String cacheKey = cacheKey(tenantId, params);

        String cached = readCacheSafely(cacheKey);
        if (cached != null) {
            SearchResponse cachedResponse = objectMapper.readValue(cached, SearchResponse.class);
            return new SearchResponse(cachedResponse.query(), cachedResponse.totalHits(), cachedResponse.page(),
                    cachedResponse.size(), cachedResponse.tookMs(), true, cachedResponse.hits(), cachedResponse.facets());
        }

        EsSearchResult result = searchIndex.search(tenantId, params);
        SearchResponse response = toResponse(params, result);

        writeCacheSafely(cacheKey, response);
        return response;
    }

    private String readCacheSafely(String cacheKey) {
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception ex) {
            log.warn("Redis read failed for key {}, falling back to Elasticsearch: {}", cacheKey, ex.getMessage());
            return null;
        }
    }

    private void writeCacheSafely(String cacheKey, SearchResponse response) {
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response), Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception ex) {
            log.warn("Redis write failed for key {}, response served without caching it: {}", cacheKey, ex.getMessage());
        }
    }

    private SearchResponse toResponse(SearchQueryParams params, EsSearchResult result) {
        List<SearchHit> hits = new ArrayList<>();
        for (Hit<EsDocument> hit : result.hits()) {
            EsDocument source = hit.source();
            List<String> highlights = new ArrayList<>();
            if (hit.highlight() != null) {
                hit.highlight().values().forEach(highlights::addAll);
            }
            hits.add(new SearchHit(
                    hit.id(),
                    source != null ? source.title() : null,
                    highlights,
                    hit.score() != null ? hit.score() : 0.0,
                    source != null ? source.tags() : List.of(),
                    source != null ? source.createdAt() : null
            ));
        }

        return new SearchResponse(params.query(), result.totalHits(), params.page(), params.size(),
                result.tookMs(), false, hits, result.facets());
    }

    private String cacheKey(String tenantId, SearchQueryParams params) {
        String raw = "%s|%s|%d|%d|%s|%s".formatted(tenantId, params.query(), params.page(), params.size(),
                params.tagsFilter(), params.fuzzy());
        return "search:" + sha256(raw);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
