package com.docsearch.controller;
import com.docsearch.service.DocumentService;

import com.docsearch.dto.DocumentRequest;
import com.docsearch.dto.DocumentResponse;
import com.docsearch.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    // USER is read-only; ADMIN has full CRUD. The role comes from a verified
    // JWT claim (see JwtAuthFilter/SecurityConfig), populated into Spring
    // Security's context as a ROLE_ADMIN/ROLE_USER authority - @PreAuthorize
    // is Spring Security's real mechanism for this, not a hand-rolled guard.
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/documents")
    public ResponseEntity<DocumentResponse> create(@Valid @RequestBody DocumentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DocumentResponse created = documentService.create(TenantContext.get(), request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/documents/" + created.id()))
                .body(created);
    }

    @GetMapping("/documents/{id}")
    public DocumentResponse get(@PathVariable UUID id) {
        return documentService.get(TenantContext.get(), id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        documentService.delete(TenantContext.get(), id);
        return ResponseEntity.noContent().build();
    }
}
