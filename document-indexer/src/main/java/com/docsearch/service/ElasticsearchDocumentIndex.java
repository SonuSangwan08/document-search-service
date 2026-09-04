package com.docsearch.service;
import com.docsearch.model.EsDocument;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.docsearch.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Thin wrapper around the Elasticsearch Java client for exactly the write
 * operations this service needs: index and delete. Query/search building
 * lives in document-service's own copy of this class - this service never
 * searches, it only writes what document-service's SearchController later
 * reads. Every call is routed on tenantId so a write only ever touches the
 * shard(s) holding that tenant's data.
 */
@Component
@Slf4j
public class ElasticsearchDocumentIndex {

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
}
