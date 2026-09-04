package com.docsearch.repository;
import com.docsearch.model.OutboxEntity;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEntity, Long> {

    List<OutboxEntity> findAllByOrderByIdAsc(Limit limit);

    /**
     * Confirmed by review: {@link #findAllByOrderByIdAsc} takes no lock, so
     * if document-service is ever scaled to 2+ instances - which
     * docs/SUBMISSION.md claims is safe, since the app tier is "stateless" -
     * two instances' OutboxRelay schedulers could select and publish the
     * same batch simultaneously. FOR UPDATE SKIP LOCKED gives concurrent
     * instances disjoint batches instead: each locks the rows it selects,
     * and any row already locked by another instance is silently excluded
     * rather than blocked on. Bounded in practice even without this - the
     * indexer's idempotent re-read design absorbs a duplicate publish - but
     * it was still a real, silent 2x-per-extra-instance amplification of
     * Kafka traffic and consumer work.
     */
    @Query(value = "SELECT * FROM document_outbox ORDER BY id ASC LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEntity> findBatchForUpdateSkipLocked(@Param("limit") int limit);
}
