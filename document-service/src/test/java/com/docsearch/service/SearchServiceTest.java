package com.docsearch.service;
import com.docsearch.dto.SearchQueryParams;
import com.docsearch.model.EsSearchResult;

import com.docsearch.config.AppProperties;
import com.docsearch.dto.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    private static final String TENANT = "acme";

    @Mock
    private ElasticsearchDocumentIndex searchIndex;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Elasticsearch("http://localhost:9200", "documents", 2000, 5000),
                new AppProperties.Kafka("document-index-events"),
                new AppProperties.Cache(30, 120),
                new AppProperties.RateLimit(120, 300, 10),
                new AppProperties.Jwt("test-only-not-used-by-search-service-directly", 60));
        searchService = new SearchService(searchIndex, redisTemplate, objectMapper, properties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void searchQueriesElasticsearchOnCacheMissAndPopulatesCache() throws Exception {
        when(valueOperations.get(anyString())).thenReturn(null);
        when(searchIndex.search(anyString(), any())).thenReturn(new EsSearchResult(0, 5, List.of(), Map.of()));

        SearchResponse response = searchService.search(TENANT, new SearchQueryParams("invoices", 0, 20, null, false));

        assertThat(response.fromCache()).isFalse();
        assertThat(response.totalHits()).isZero();
        verify(searchIndex, times(1)).search(anyString(), any());
        verify(valueOperations, times(1)).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void searchReturnsCachedResultWithoutQueryingElasticsearchOnCacheHit() throws Exception {
        SearchResponse cached = new SearchResponse("invoices", 3, 0, 20, 5, false, List.of(), Map.of());
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(cached));

        SearchResponse response = searchService.search(TENANT, new SearchQueryParams("invoices", 0, 20, null, false));

        assertThat(response.fromCache()).isTrue();
        assertThat(response.totalHits()).isEqualTo(3);
        verify(searchIndex, never()).search(anyString(), any());
    }
}
