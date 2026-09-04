package com.docsearch.messaging;
import com.docsearch.model.OutboxEntity;
import com.docsearch.repository.OutboxRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Polls the outbox table and relays rows to Kafka. A simple fixed-delay poll
 * is enough for a prototype at this volume; the production equivalent is a
 * log-based CDC connector (e.g. Debezium tailing the Postgres WAL) so
 * publish latency isn't bound by a poll interval - see
 * docs/PRODUCTION_READINESS.md.
 */
@Component
@Slf4j
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepository;
    private final DocumentEventProducer eventProducer;

    public OutboxRelay(OutboxRepository outboxRepository, DocumentEventProducer eventProducer) {
        this.outboxRepository = outboxRepository;
        this.eventProducer = eventProducer;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:200}")
    @Transactional
    public void relay() {
        List<OutboxEntity> batch = outboxRepository.findBatchForUpdateSkipLocked(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEntity row : batch) {
            eventProducer.publish(new DocumentEvent(row.getDocumentId(), row.getTenantId(), row.getEventType()));
        }
        outboxRepository.deleteAllInBatch(batch);
        log.debug("Relayed {} outbox events to Kafka", batch.size());
    }
}
