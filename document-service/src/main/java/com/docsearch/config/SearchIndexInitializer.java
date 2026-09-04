package com.docsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.docsearch.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Creates the Elasticsearch index with an explicit mapping on first boot.
 * A prototype convenience - in production, index creation and mapping
 * changes go through a versioned migration tool (e.g. an index-per-mapping-
 * version + alias swap), never implicit "create on startup" logic.
 */
@Component
@Slf4j
public class SearchIndexInitializer implements ApplicationRunner {

    private final ElasticsearchClient client;
    private final String indexName;

    public SearchIndexInitializer(ElasticsearchClient client, AppProperties properties) {
        this.client = client;
        this.indexName = properties.elasticsearch().indexName();
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        boolean exists = client.indices().exists(e -> e.index(indexName)).value();
        if (exists) {
            log.info("Elasticsearch index '{}' already exists", indexName);
            return;
        }

        client.indices().create(c -> c
                .index(indexName)
                .settings(s -> s
                        .numberOfShards("3")
                        .numberOfReplicas("1"))
                .mappings(m -> m
                        .properties("tenantId", p -> p.keyword(k -> k))
                        .properties("title", p -> p.text(t -> t
                                .analyzer("standard")
                                .fields("keyword", f -> f.keyword(k -> k.ignoreAbove(256)))))
                        .properties("content", p -> p.text(t -> t.analyzer("standard")))
                        .properties("tags", p -> p.keyword(k -> k))
                        // metadata is a client-controlled, arbitrary-key map living in a
                        // shared index across tenants - dynamic mapping on it would let one
                        // tenant's metadata keys blow up the index's field-count limit for
                        // everyone. Store it (for round-tripping) but never map/index it.
                        .properties("metadata", p -> p.object(o -> o.enabled(false)))
                        .properties("department", p -> p.keyword(k -> k))
                        .properties("category", p -> p.keyword(k -> k))
                        .properties("docType", p -> p.keyword(k -> k))
                        .properties("sizeBucket", p -> p.keyword(k -> k))
                        .properties("createdAt", p -> p.date(d -> d))
                        .properties("updatedAt", p -> p.date(d -> d))));

        log.info("Created Elasticsearch index '{}'", indexName);
    }
}
