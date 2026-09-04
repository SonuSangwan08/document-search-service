package com.docsearch.messaging;

import com.docsearch.config.AppProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DocumentEventProducer {

    private final KafkaTemplate<String, DocumentEvent> kafkaTemplate;
    private final String topic;

    public DocumentEventProducer(KafkaTemplate<String, DocumentEvent> kafkaTemplate, AppProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = properties.kafka().documentEventsTopic();
    }

    /**
     * Keyed by document id so Kafka's per-partition ordering guarantees that
     * two events for the same document (e.g. INDEX then DELETE) are always
     * consumed in the order they were produced.
     * <p>
     * Confirmed by review: {@code kafkaTemplate.send(...)} returns a
     * {@link java.util.concurrent.CompletableFuture} that was previously
     * discarded here - fire-and-forget. The already-fixed {@code
     * max.block.ms} issue only covers the synchronous portion of send()
     * (waiting for topic metadata); a failure reported only through this
     * future (e.g. {@code acks=all} failing because in-sync-replica count
     * drops, or retries exhausting) would never reach the caller. Since
     * {@link OutboxRelay#relay()} deletes the outbox row right after calling
     * this, a swallowed async failure meant the row could be deleted before
     * the message was ever durably produced - silent, permanent loss of the
     * one record that "this needs indexing." Blocking here (bounded by the
     * request timeout) lets a real failure propagate and abort relay()'s
     * transaction, exactly like the synchronous-timeout case.
     */
    public void publish(DocumentEvent event) {
        try {
            kafkaTemplate.send(topic, event.documentId().toString(), event).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed to publish " + event, ex.getCause() != null ? ex.getCause() : ex);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("Timed out publishing " + event, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing " + event, ex);
        }
    }
}
