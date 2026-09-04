package com.docsearch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata = Map.of();

    // Fixed, dropdown-driven facet fields - department/category/docType are
    // client-supplied from a known set; sizeBucket is derived server-side
    // from content length (see DocumentService) and never client-settable
    // via DocumentRequest. Unlike `metadata`, these are safe to map/facet in
    // Elasticsearch since their keys are bounded, not arbitrary.
    @Column
    private String department;

    @Column
    private String category;

    @Column(name = "doc_type")
    private String docType;

    @Column(name = "size_bucket")
    private String sizeBucket;

    // Optional client-supplied replay-protection key for POST /documents;
    // see the unique partial index in V2__add_idempotency_key.sql.
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status = DocumentStatus.PENDING;

    // Optimistic locking: a concurrent update racing on the same document id
    // fails fast with a 409 instead of silently overwriting a sibling change.
    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public DocumentEntity(String tenantId, String title, String content, List<String> tags,
                           Map<String, Object> metadata) {
        this.tenantId = tenantId;
        this.title = title;
        this.content = content;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.metadata = metadata != null ? metadata : Map.of();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
