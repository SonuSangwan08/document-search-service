package com.docsearch.dto;

import com.docsearch.model.DocumentEntity;
import com.docsearch.model.DocumentStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String tenantId,
        String title,
        String content,
        List<String> tags,
        Map<String, Object> metadata,
        String department,
        String category,
        String docType,
        String sizeBucket,
        DocumentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static DocumentResponse from(DocumentEntity entity) {
        return new DocumentResponse(
                entity.getId(),
                entity.getTenantId(),
                entity.getTitle(),
                entity.getContent(),
                entity.getTags(),
                entity.getMetadata(),
                entity.getDepartment(),
                entity.getCategory(),
                entity.getDocType(),
                entity.getSizeBucket(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
