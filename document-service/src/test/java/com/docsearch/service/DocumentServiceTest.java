package com.docsearch.service;
import com.docsearch.exception.IdempotencyConflictException;
import com.docsearch.model.DocumentEntity;
import com.docsearch.model.DocumentStatus;
import com.docsearch.repository.DocumentRepository;

import com.docsearch.config.AppProperties;
import com.docsearch.dto.DocumentRequest;
import com.docsearch.dto.DocumentResponse;
import com.docsearch.exception.DocumentNotFoundException;
import com.docsearch.model.OutboxEntity;
import com.docsearch.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final String TENANT = "acme";

    @Mock
    private DocumentRepository documentRepository;
    @Mock
    private OutboxRepository outboxRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Elasticsearch("http://localhost:9200", "documents", 2000, 5000),
                new AppProperties.Kafka("document-index-events"),
                new AppProperties.Cache(30, 120),
                new AppProperties.RateLimit(120, 300, 10),
                new AppProperties.Jwt("test-only-not-used-by-document-service-directly", 60));
        documentService = new DocumentService(documentRepository, outboxRepository, redisTemplate, objectMapper, properties);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void createPersistsDocumentAndOutboxEventInPendingState() {
        DocumentRequest request = new DocumentRequest("Title", "Body", null, null);

        DocumentResponse response = documentService.create(TENANT, request);

        assertThat(response.status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(response.tenantId()).isEqualTo(TENANT);

        verify(documentRepository).save(any(DocumentEntity.class));

        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(OutboxEntity.EventType.INDEX);
        assertThat(outboxCaptor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void createWithNewIdempotencyKeyCreatesDocumentAsNormal() {
        DocumentRequest request = new DocumentRequest("Title", "Body", null, null);
        when(documentRepository.findByTenantIdAndIdempotencyKey(TENANT, "key-1")).thenReturn(Optional.empty());

        DocumentResponse response = documentService.create(TENANT, request, "key-1");

        assertThat(response.status()).isEqualTo(DocumentStatus.PENDING);
        // saveAndFlush, not save, on the idempotency-key path specifically -
        // see IdempotencyConflictException's javadoc for why.
        verify(documentRepository).saveAndFlush(any(DocumentEntity.class));
        verify(outboxRepository).save(any(OutboxEntity.class));
    }

    @Test
    void createWithRepeatedIdempotencyKeyReturnsExistingDocumentWithoutDuplicating() {
        DocumentRequest request = new DocumentRequest("Title", "Body", null, null);
        DocumentEntity existing = new DocumentEntity(TENANT, "Original Title", "Original Body", null, null);
        when(documentRepository.findByTenantIdAndIdempotencyKey(TENANT, "key-1")).thenReturn(Optional.of(existing));

        DocumentResponse response = documentService.create(TENANT, request, "key-1");

        assertThat(response.title()).isEqualTo("Original Title");
        verify(documentRepository, never()).save(any(DocumentEntity.class));
        verify(outboxRepository, never()).save(any(OutboxEntity.class));
    }

    @Test
    void createWithConcurrentIdempotencyKeyRaceThrowsIdempotencyConflict() {
        // Simulates the race: this request's own check-then-insert sees no
        // existing row (a concurrent winner hasn't committed yet from this
        // request's point of view), but the DB's unique index rejects the
        // insert anyway once it actually runs.
        DocumentRequest request = new DocumentRequest("Title", "Body", null, null);
        when(documentRepository.findByTenantIdAndIdempotencyKey(TENANT, "key-1")).thenReturn(Optional.empty());
        when(documentRepository.saveAndFlush(any(DocumentEntity.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> documentService.create(TENANT, request, "key-1"))
                .isInstanceOf(IdempotencyConflictException.class)
                .satisfies(ex -> {
                    IdempotencyConflictException conflict = (IdempotencyConflictException) ex;
                    assertThat(conflict.tenantId()).isEqualTo(TENANT);
                    assertThat(conflict.idempotencyKey()).isEqualTo("key-1");
                });

        verify(outboxRepository, never()).save(any(OutboxEntity.class));
    }

    @Test
    void findByIdempotencyKeyReturnsExistingDocumentResponse() {
        DocumentEntity existing = new DocumentEntity(TENANT, "Original Title", "Original Body", null, null);
        when(documentRepository.findByTenantIdAndIdempotencyKey(TENANT, "key-1")).thenReturn(Optional.of(existing));

        Optional<DocumentResponse> result = documentService.findByIdempotencyKey(TENANT, "key-1");

        assertThat(result).isPresent();
        assertThat(result.get().title()).isEqualTo("Original Title");
    }

    @Test
    void getReturnsCachedResponseWithoutHittingRepositoryOnCacheHit() {
        UUID id = UUID.randomUUID();
        DocumentResponse cached = new DocumentResponse(id, TENANT, "Cached Title", "Body", null, null,
                null, null, null, null, DocumentStatus.INDEXED, null, null);
        when(valueOperations.get(anyString())).thenReturn(objectMapper.writeValueAsString(cached));

        DocumentResponse result = documentService.get(TENANT, id);

        assertThat(result.title()).isEqualTo("Cached Title");
        verify(documentRepository, never()).findByIdAndTenantIdAndDeletedAtIsNull(any(), anyString());
    }

    @Test
    void getFallsBackToRepositoryAndPopulatesCacheOnMiss() {
        UUID id = UUID.randomUUID();
        DocumentEntity entity = new DocumentEntity(TENANT, "Title", "Body", null, null);
        when(valueOperations.get(anyString())).thenReturn(null);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TENANT)).thenReturn(Optional.of(entity));

        DocumentResponse result = documentService.get(TENANT, id);

        assertThat(result.title()).isEqualTo("Title");
        verify(valueOperations, times(1)).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void getThrowsWhenDocumentDoesNotExistForTenant() {
        UUID id = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenReturn(null);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TENANT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.get(TENANT, id))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void deleteSoftDeletesAndQueuesDeleteEvent() {
        UUID id = UUID.randomUUID();
        DocumentEntity entity = new DocumentEntity(TENANT, "Title", "Body", null, null);
        when(documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, TENANT)).thenReturn(Optional.of(entity));

        documentService.delete(TENANT, id);

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getStatus()).isEqualTo(DocumentStatus.DELETED);

        ArgumentCaptor<OutboxEntity> outboxCaptor = ArgumentCaptor.forClass(OutboxEntity.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(OutboxEntity.EventType.DELETE);
    }
}
