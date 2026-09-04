package com.docsearch.repository;
import com.docsearch.model.TenantEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<TenantEntity, String> {

    boolean existsByIdAndStatus(String id, TenantEntity.TenantStatus status);
}
