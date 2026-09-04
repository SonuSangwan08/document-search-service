package com.docsearch.dto;

import java.util.List;

public record SearchQueryParams(
        String query,
        int page,
        int size,
        List<String> tagsFilter,
        boolean fuzzy,
        String department,
        String category,
        String docType
) {
    public SearchQueryParams(String query, int page, int size, List<String> tagsFilter, boolean fuzzy) {
        this(query, page, size, tagsFilter, fuzzy, null, null, null);
    }

    public int from() {
        return page * size;
    }
}
