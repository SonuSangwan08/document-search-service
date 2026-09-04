package com.docsearch.messaging;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.docsearch.model.DocumentEntity;
import com.docsearch.repository.DocumentRepository;
import com.docsearch.model.DocumentStatus;
import com.docsearch.service.ElasticsearchDocumentIndex;
import com.docsearch.model.EsDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@link DocumentEvent}s produced by document-service's OutboxRelay
 * and applies them to Elasticsearch. Re-reads the current row from Postgres
 * rather than trusting the event payload, which makes the handler naturally
 * idempotent under Kafka's at-least-once delivery: replaying the same event
 * twice just re-indexes the same current state.
 * <p>
 * This is the whole reason document-indexer is a separate service from
 * document-service: indexing throughput scales independently of API
 * request throughput by running more instances of just this consumer group
 * member (bounded by the document-index-events topic's partition count),
 * without paying for extra copies of the REST API tier.
 * <p>
 * A production version of this would route to a dead-letter topic with
 * backoff after N failed attempts (see docs/SUBMISSION.md); this prototype
 * logs and marks the row INDEX_FAILED so the failure is at least visible
 * and queryable, rather than silently retried forever.
 */
@Component
@Slf4j
public class DocumentIndexConsumer {

    private final DocumentRepository documentRepository;
    private final ElasticsearchDocumentIndex searchIndex;

    public DocumentIndexConsumer(DocumentRepository documentRepository, ElasticsearchDocumentIndex searchIndex) {
        this.documentRepository = documentRepository;
        this.searchIndex = searchIndex;
    }

    @KafkaListener(topics = "${app.kafka.document-events-topic}")
    @Transactional
    public void onEvent(DocumentEvent event, Acknowledgment acknowledgment) {
        try {
            switch (event.eventType()) {
                case INDEX -> handleIndex(event);
                case DELETE -> handleDelete(event);
            }
        } catch (Exception ex) {
            log.error("Failed to apply {} event for document {} (tenant {}): {}",
                    event.eventType(), event.documentId(), event.tenantId(), ex.getMessage(), ex);
            // Confirmed by review: only INDEX failures were marked before -
            // a failed DELETE was logged and silently dropped, leaving the
            // document DELETED in Postgres but still indexed/searchable in
            // Elasticsearch forever with no marker at all. DELETE_FAILED
            // mirrors the INDEX_FAILED handling below exactly.
            DocumentStatus failureStatus = event.eventType() == DocumentEvent.EventType.INDEX
                    ? DocumentStatus.INDEX_FAILED
                    : DocumentStatus.DELETE_FAILED;
            documentRepository.findById(event.documentId())
                    .ifPresent(doc -> {
                        doc.setStatus(failureStatus);
                        documentRepository.save(doc);
                    });
        } finally {
            acknowledgment.acknowledge();
        }
    }

    private void handleIndex(DocumentEvent event) throws java.io.IOException {
        DocumentEntity entity = documentRepository.findById(event.documentId()).orElse(null);
        if (entity == null || entity.isDeleted()) {
            log.debug("Skipping index event for missing/deleted document {}", event.documentId());
            return;
        }

        searchIndex.index(EsDocument.from(entity));
        entity.setStatus(DocumentStatus.INDEXED);
        documentRepository.save(entity);
        log.info("Indexed document {} for tenant {}", entity.getId(), entity.getTenantId());
    }

    private void handleDelete(DocumentEvent event) throws java.io.IOException {
        try {
            searchIndex.delete(event.tenantId(), event.documentId().toString());
            log.info("Removed document {} from index for tenant {}", event.documentId(), event.tenantId());
        } catch (ElasticsearchException ex) {
            if (ex.status() == 404) {
                // Already absent from the index (e.g. it was never successfully
                // indexed before being deleted) - the goal state is achieved.
                log.debug("Document {} already absent from index, treating delete as a no-op", event.documentId());
                return;
            }
            throw ex;
        }
    }
}
