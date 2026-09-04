package com.docsearch.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces Elasticsearch cluster health under GET /actuator/health - more
 * load-bearing here than on document-service, since this service's entire
 * job is writing to Elasticsearch. Postgres and Kafka health are covered by
 * Spring Boot's own auto-configured indicators.
 */
@Component
public class ElasticsearchHealthIndicator implements HealthIndicator {

    private final ElasticsearchClient client;

    public ElasticsearchHealthIndicator(ElasticsearchClient client) {
        this.client = client;
    }

    @Override
    public Health health() {
        try {
            HealthResponse response = client.cluster().health();
            Health.Builder builder = switch (response.status()) {
                case Green, Yellow -> Health.up();
                case Red -> Health.down();
            };
            return builder
                    .withDetail("cluster", response.clusterName())
                    .withDetail("status", response.status().jsonValue())
                    .withDetail("numberOfNodes", response.numberOfNodes())
                    .build();
        } catch (Exception ex) {
            return Health.down(ex).build();
        }
    }
}
