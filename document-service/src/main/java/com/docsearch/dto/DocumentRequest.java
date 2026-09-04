package com.docsearch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record DocumentRequest(
        @NotBlank @Size(max = 512) String title,
        @NotBlank @Size(max = 500_000) String content,
        @Size(max = 50) List<String> tags,
        Map<String, Object> metadata,
        @Size(max = 128) String department,
        @Size(max = 128) String category,
        @Size(max = 128) String docType
) {
    public DocumentRequest(String title, String content, List<String> tags, Map<String, Object> metadata) {
        this(title, content, tags, metadata, null, null, null);
    }
}
