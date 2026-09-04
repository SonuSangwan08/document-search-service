package com.docsearch.dto;

import java.time.Instant;
import java.util.List;

public record SearchHit(
        String id,
        String title,
        List<String> highlights,
        double score,
        List<String> tags,
        Instant createdAt
) {
}
