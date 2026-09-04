package com.docsearch.messaging;
import com.docsearch.model.OutboxEntity;

import java.util.UUID;

/**
 * Kafka payload published by {@link OutboxRelay} and consumed by
 * {@link DocumentIndexConsumer}. Intentionally carries only identifiers, not
 * document content - the consumer re-reads the current row from Postgres
 * before indexing, so a burst of rapid edits collapses to "index whatever is
 * current" instead of replaying stale intermediate versions out of order.
 */
public record DocumentEvent(UUID documentId, String tenantId, OutboxEntity.EventType eventType) {
}
