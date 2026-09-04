package com.docsearch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row. Written in the SAME database transaction as the
 * {@code documents} row it describes, so "document saved" and "event queued
 * for publish" are atomic - there is no window where a crash between the two
 * loses the indexing event (the classic dual-write problem with directly
 * publishing to Kafka inside a DB transaction).
 */
@Entity
@Table(name = "document_outbox")
@Getter
@NoArgsConstructor
public class OutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private EventType eventType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OutboxEntity(UUID documentId, String tenantId, EventType eventType) {
        this.documentId = documentId;
        this.tenantId = tenantId;
        this.eventType = eventType;
    }

    public enum EventType {
        INDEX, DELETE
    }
}
