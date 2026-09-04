package com.docsearch.controller;
import com.docsearch.dto.SearchQueryParams;
import com.docsearch.service.SearchService;

import com.docsearch.dto.SearchResponse;
import com.docsearch.security.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.List;

@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    // Elasticsearch's default max_result_window (from-plus-size per query).
    // See docs/SUBMISSION.md's Performance section - deep pagination past
    // this needs search_after, not from/size, which this API doesn't expose.
    private static final int MAX_RESULT_WINDOW = 10_000;

    /**
     * The assignment spec shows {@code tenant} as a query parameter; the
     * actual, security-enforced tenant for every request is the one
     * resolved from the verified JWT claim by JwtAuthFilter (a query param
     * is trivially spoofable by changing the URL, so it can never be the
     * source of truth for tenant isolation - unlike the trusted-header model
     * this replaced, there isn't even a header left for a client to spoof;
     * tenant identity now only ever comes from a signed token). The param is
     * still accepted for contract compatibility - if present, it must agree
     * with the token's tenant or the request is rejected.
     */
    @GetMapping("/search")
    public SearchResponse search(
            @RequestParam(name = "q", required = false, defaultValue = "") String q,
            @RequestParam(required = false) String tenant,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "false") boolean fuzzy,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String docType) throws IOException {

        String tenantId = TenantContext.get();
        if (tenant != null && !tenant.equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenant query param '" + tenant + "' does not match X-Tenant-Id header '" + tenantId + "'");
        }

        int boundedSize = Math.min(Math.max(size, 1), 100);
        int boundedPage = Math.max(page, 0);

        // Confirmed by review: with size clamped but page only floored at 0,
        // (long) page * size could exceed Elasticsearch's max_result_window
        // or, for a very large page, overflow int - either produced an
        // uncaught exception that fell through to a generic 500 instead of
        // a proper 400.
        if ((long) boundedPage * boundedSize > MAX_RESULT_WINDOW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page " + boundedPage + " with size " + boundedSize + " exceeds the " + MAX_RESULT_WINDOW
                            + "-result deep-pagination limit - use a smaller page/size, or search_after "
                            + "pagination (see docs/SUBMISSION.md Performance) for a future deep-pagination endpoint");
        }

        SearchQueryParams params = new SearchQueryParams(q, boundedPage, boundedSize, tags, fuzzy,
                department, category, docType);
        return searchService.search(tenantId, params);
    }
}
