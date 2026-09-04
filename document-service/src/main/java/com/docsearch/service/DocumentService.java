package com.docsearch.service;
import com.docsearch.exception.IdempotencyConflictException;
import com.docsearch.model.DocumentStatus;
import com.docsearch.model.DocumentEntity;
import com.docsearch.repository.DocumentRepository;

import com.docsearch.config.AppProperties;
import com.docsearch.dto.DocumentRequest;
import com.docsearch.dto.DocumentResponse;
import com.docsearch.exception.DocumentNotFoundException;
import com.docsearch.model.OutboxEntity;
import com.docsearch.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final OutboxRepository outboxRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public DocumentService(DocumentRepository documentRepository, OutboxRepository outboxRepository,
                            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, AppProperties properties) {
        this.documentRepository = documentRepository;
        this.outboxRepository = outboxRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Writes the document row and its outbox event in one DB transaction
     * (see OutboxEntity) and returns immediately - indexing into
     * Elasticsearch happens asynchronously via the outbox relay + Kafka
     * consumer. Callers should treat the returned status (PENDING) as
     * "accepted, not yet searchable".
     */
    @Transactional
    public DocumentResponse create(String tenantId, DocumentRequest request) {
        return create(tenantId, request, null);
    }

    /**
     * @param idempotencyKey optional client-supplied key (Idempotency-Key header) - a
     *                       repeated key for the same tenant returns the original
     *                       document instead of creating a duplicate. Omitting it just
     *                       means no replay protection, same as before this existed.
     */
    @Transactional
    public DocumentResponse create(String tenantId, DocumentRequest request, String idempotencyKey) {
        boolean hasIdempotencyKey = idempotencyKey != null && !idempotencyKey.isBlank();
        if (hasIdempotencyKey) {
            Optional<DocumentEntity> existing =
                    documentRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency key {} already used for tenant {}, returning existing document {}",
                        idempotencyKey, tenantId, existing.get().getId());
                return DocumentResponse.from(existing.get());
            }
        }

        DocumentEntity entity = new DocumentEntity(tenantId, request.title(), request.content(),
                request.tags(), request.metadata());
        entity.setDepartment(request.department());
        entity.setCategory(request.category());
        entity.setDocType(request.docType());
        entity.setSizeBucket(sizeBucketOf(request.content()));
        if (hasIdempotencyKey) {
            entity.setIdempotencyKey(idempotencyKey);
        }

        if (hasIdempotencyKey) {
            // saveAndFlush (not save) specifically here: this forces the
            // unique-index violation to surface synchronously, inside this
            // try block, instead of being deferred to end-of-transaction
            // flush - by which point it's too late to catch cleanly and
            // distinguish "idempotency race" from any other failure.
            try {
                documentRepository.saveAndFlush(entity);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                // The transaction is now aborted by Postgres and cannot run
                // further queries - GlobalExceptionHandler re-queries for
                // the winning document in a fresh transaction after this
                // one has rolled back.
                throw new IdempotencyConflictException(tenantId, idempotencyKey);
            }
        } else {
            documentRepository.save(entity);
        }
        outboxRepository.save(new OutboxEntity(entity.getId(), tenantId, OutboxEntity.EventType.INDEX));

        log.info("Document {} created for tenant {}, queued for indexing", entity.getId(), tenantId);
        return DocumentResponse.from(entity);
    }

    /**
     * Used by {@link com.docsearch.exception.GlobalExceptionHandler}
     * to resolve an {@link IdempotencyConflictException} - runs in whatever
     * transaction that handler is invoked in (a new one, since the original
     * create() transaction already rolled back).
     */
    public Optional<DocumentResponse> findByIdempotencyKey(String tenantId, String idempotencyKey) {
        return documentRepository.findByTenantIdAndIdempotencyKey(tenantId, idempotencyKey)
                .map(DocumentResponse::from);
    }

    private static String sizeBucketOf(String content) {
        int length = content != null ? content.length() : 0;
        if (length < 10_000) {
            return "SMALL";
        }
        if (length < 100_000) {
            return "MEDIUM";
        }
        return "LARGE";
    }

    /**
     * Cache reads/writes are wrapped and never allowed to fail the request -
     * confirmed live (see docs/SUBMISSION.md §2 Resilience) that without this,
     * a Redis outage took down GET /documents/{id} entirely with a 500, even
     * though Redis here is purely a latency optimization backed by Postgres
     * as the real source of truth. Fail open: log and fall through to
     * Postgres on a cache-read failure, log and skip on a cache-write
     * failure - either way the request still succeeds.
     */
    public DocumentResponse get(String tenantId, UUID id) {
        String cacheKey = documentCacheKey(tenantId, id);
        String cached = readCacheSafely(cacheKey);
        if (cached != null) {
            return objectMapper.readValue(cached, DocumentResponse.class);
        }

        DocumentEntity entity = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        DocumentResponse response = DocumentResponse.from(entity);
        writeCacheSafely(cacheKey, response);
        return response;
    }

    @Transactional
    public void delete(String tenantId, UUID id) {
        DocumentEntity entity = documentRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        entity.setDeletedAt(java.time.Instant.now());
        entity.setStatus(DocumentStatus.DELETED);
        outboxRepository.save(new OutboxEntity(entity.getId(), tenantId, OutboxEntity.EventType.DELETE));

        try {
            redisTemplate.delete(documentCacheKey(tenantId, id));
        } catch (Exception ex) {
            log.warn("Cache eviction failed for document {} (tenant {}) - a stale cached copy may be served "
                    + "for up to the document TTL: {}", id, tenantId, ex.getMessage());
        }
        log.info("Document {} soft-deleted for tenant {}, queued for de-indexing", id, tenantId);
    }

    private String readCacheSafely(String cacheKey) {
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception ex) {
            log.warn("Redis read failed for key {}, falling back to Postgres: {}", cacheKey, ex.getMessage());
            return null;
        }
    }

    private void writeCacheSafely(String cacheKey, DocumentResponse response) {
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response),
                    Duration.ofSeconds(properties.cache().documentTtlSeconds()));
        } catch (Exception ex) {
            log.warn("Redis write failed for key {}, response served without caching it: {}", cacheKey, ex.getMessage());
        }
    }

    private String documentCacheKey(String tenantId, UUID id) {
        return "doc:%s:%s".formatted(tenantId, id);
    }
}
