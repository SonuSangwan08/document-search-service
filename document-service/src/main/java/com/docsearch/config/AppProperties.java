package com.docsearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Elasticsearch elasticsearch,
        Kafka kafka,
        Cache cache,
        RateLimit rateLimit,
        Jwt jwt
) {
    public record Elasticsearch(
            String uris,
            String indexName,
            int connectTimeoutMs,
            int socketTimeoutMs
    ) {}

    public record Kafka(String documentEventsTopic) {}

    public record Cache(long searchResultsTtlSeconds, long documentTtlSeconds) {}

    public record RateLimit(int defaultRequestsPerMinute, int searchRequestsPerMinute, int loginRequestsPerMinute) {}

    // Self-issued JWT signing config. `secret` is a locally-held HMAC-SHA256
    // key (must be >= 32 bytes) - a real production deployment would verify
    // tokens issued by an external IdP via JWKS instead of signing them
    // itself; see docs/SUBMISSION.md Security section.
    public record Jwt(String secret, long expirationMinutes) {}
}
