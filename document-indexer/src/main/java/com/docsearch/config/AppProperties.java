package com.docsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Elasticsearch elasticsearch,
        Kafka kafka
) {
    public record Elasticsearch(
            String uris,
            String indexName,
            int connectTimeoutMs,
            int socketTimeoutMs
    ) {}

    public record Kafka(String documentEventsTopic) {}
}
