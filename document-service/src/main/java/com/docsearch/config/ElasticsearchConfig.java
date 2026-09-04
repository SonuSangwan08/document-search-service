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
 * Elasticsearch) so query building - routing by tenant, highlighting,
 * fuzziness, aggregations - stays explicit and inspectable rather than
 * hidden behind repository-derived queries.
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
        // A private Jackson 2 mapper, deliberately not Spring's Jackson 3 bean -
        // see the class-level note on JacksonJsonpMapper's Jackson 2 requirement.
        // WRITE_DATES_AS_TIMESTAMPS is on by default, which serializes Instant as
        // a raw epoch number (e.g. 1.788413132969554E9) - Elasticsearch's `date`
        // mapping rejects that; disabling it gets ISO-8601 strings instead, which
        // ES parses natively.
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
