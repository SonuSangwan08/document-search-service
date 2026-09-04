package com.docsearch.repository;
import com.docsearch.model.DocumentEntity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

    Optional<DocumentEntity> findByIdAndTenantIdAndDeletedAtIsNull(UUID id, String tenantId);

    Optional<DocumentEntity> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}
