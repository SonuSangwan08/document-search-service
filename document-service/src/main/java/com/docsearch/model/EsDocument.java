package com.docsearch.model;

import com.docsearch.model.DocumentEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The Elasticsearch-side projection of a document. Deliberately narrower
 * than {@link DocumentEntity} - only what search/ranking/faceting needs is
 * duplicated into the index; everything else is fetched from Postgres on
 * demand via GET /documents/{id}.
 */
public record EsDocument(
        String id,
        String tenantId,
        String title,
        String content,
        List<String> tags,
        Map<String, Object> metadata,
        String department,
        String category,
        String docType,
        String sizeBucket,
        Instant createdAt,
        Instant updatedAt
) {
    public static EsDocument from(DocumentEntity entity) {
        return new EsDocument(
                entity.getId().toString(),
                entity.getTenantId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getTags(),
                entity.getMetadata(),
                entity.getDepartment(),
                entity.getCategory(),
                entity.getDocType(),
                entity.getSizeBucket(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
