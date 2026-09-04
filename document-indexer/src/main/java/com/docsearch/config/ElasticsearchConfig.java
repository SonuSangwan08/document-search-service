package com.docsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

/**
 * The Elasticsearch Java client is wired directly (not through Spring Data
 * Elasticsearch), matching document-service's own ElasticsearchConfig.
 * Spring Boot 4 / Framework 7 default to Jackson 3 (tools.jackson.*) for the
 * app's own JSON handling (Kafka message deserialization here), but the
 * Elasticsearch Java client predates Jackson 3 and its JacksonJsonpMapper
 * hard-requires classic Jackson 2 (com.fasterxml.jackson.*) - so a private,
 * isolated Jackson 2 mapper is wired for ES only, same as document-service.
 */
@Configuration
public class ElasticsearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient restClient(AppProperties properties) {
        var hosts = Arrays.stream(properties.elasticsearch().uris().split(","))
                .map(org.apache.http.HttpHost::create)
                .toArray(org.apache.http.HttpHost[]::new);

        return RestClient.builder(hosts)
                .setRequestConfigCallback(builder -> builder
                        .setConnectTimeout(properties.elasticsearch().connectTimeoutMs())
                        .setSocketTimeout(properties.elasticsearch().socketTimeoutMs()))
                .setHttpClientConfigCallback(HttpAsyncClientBuilder::disableAuthCaching)
                .build();
    }

    @Bean
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient) {
        // WRITE_DATES_AS_TIMESTAMPS is on by default, which serializes Instant as
        // a raw epoch number (e.g. 1.788413132969554E9) - Elasticsearch's `date`
        // mapping rejects that; disabling it gets ISO-8601 strings instead, which
        // ES parses natively. (This is the bug that produced INDEX_FAILED on the
        // very first live end-to-end run - see document-indexer/README.md.)
        ObjectMapper esObjectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new RestClientTransport(restClient, new JacksonJsonpMapper(esObjectMapper));
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
