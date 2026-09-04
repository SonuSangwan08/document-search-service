package com.docsearch.messaging;

import com.docsearch.model.DocumentEntity;
import com.docsearch.repository.DocumentRepository;
import com.docsearch.model.DocumentStatus;
import com.docsearch.service.ElasticsearchDocumentIndex;
import com.docsearch.model.EsDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIndexConsumerTest {

    private static final String TENANT = "acme";

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private ElasticsearchDocumentIndex searchIndex;
    @Mock
    private Acknowledgment acknowledgment;

    private DocumentIndexConsumer consumer;

    private DocumentIndexConsumer newConsumer() {
        return new DocumentIndexConsumer(documentRepository, searchIndex);
    }

    private DocumentEntity newEntity(UUID id, boolean deleted) {
        DocumentEntity entity = new DocumentEntity();
        entity.setId(id);
        entity.setTenantId(TENANT);
        entity.setTitle("Title");
        entity.setContent("Body");
        entity.setStatus(DocumentStatus.PENDING);
        if (deleted) {
            entity.setDeletedAt(Instant.now());
        }
        return entity;
    }

    @Test
    void indexEventIndexesFoundNonDeletedDocumentAndMarksIndexed() throws Exception {
        consumer = newConsumer();
        UUID id = UUID.randomUUID();
        DocumentEntity entity = newEntity(id, false);
        when(documentRepository.findById(id)).thenReturn(Optional.of(entity));

        consumer.onEvent(new DocumentEvent(id, TENANT, DocumentEvent.EventType.INDEX), acknowledgment);

        verify(searchIndex).index(any(EsDocument.class));
        ArgumentCaptor<DocumentEntity> saved = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DocumentStatus.INDEXED);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void indexEventForMissingDocumentIsANoOp() throws Exception {
        consumer = newConsumer();
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        consumer.onEvent(new DocumentEvent(id, TENANT, DocumentEvent.EventType.INDEX), acknowledgment);

        verify(searchIndex, never()).index(any());
        verify(documentRepository, never()).save(any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void indexEventForDeletedDocumentIsANoOp() throws Exception {
        consumer = newConsumer();
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.of(newEntity(id, true)));

        consumer.onEvent(new DocumentEvent(id, TENANT, DocumentEvent.EventType.INDEX), acknowledgment);

        verify(searchIndex, never()).index(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void deleteEventRemovesFromIndex() throws Exception {
        consumer = newConsumer();
        UUID id = UUID.randomUUID();

        consumer.onEvent(new DocumentEvent(id, TENANT, DocumentEvent.EventType.DELETE), acknowledgment);

        verify(searchIndex).delete(TENANT, id.toString());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void failedDeleteEventMarksDocumentDeleteFailedInsteadOfSilentlyDropping() throws Exception {
        consumer = newConsumer();
        UUID id = UUID.randomUUID();
        DocumentEntity entity = newEntity(id, true);
        doThrow(new java.io.IOException("Elasticsearch unreachable")).when(searchIndex).delete(TENANT, id.toString());
        when(documentRepository.findById(id)).thenReturn(Optional.of(entity));

        consumer.onEvent(new DocumentEvent(id, TENANT, DocumentEvent.EventType.DELETE), acknowledgment);

        ArgumentCaptor<DocumentEntity> saved = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(DocumentStatus.DELETE_FAILED);
        // Still acked - same at-least-once/no-infinite-retry tradeoff as INDEX_FAILED.
        verify(acknowledgment).acknowledge();
    }
}
