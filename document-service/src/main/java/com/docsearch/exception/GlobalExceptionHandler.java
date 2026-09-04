package com.docsearch.exception;
import com.docsearch.dto.ApiError;

import com.docsearch.service.DocumentService;
import com.docsearch.exception.IdempotencyConflictException;
import com.docsearch.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final DocumentService documentService;

    public GlobalExceptionHandler(DocumentService documentService) {
        this.documentService = documentService;
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(DocumentNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiError.of(429, "Too Many Requests", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "Validation Failed", message, request.getRequestURI()));
    }

    // Correction, found via live testing: this assumption was wrong.
    // @PreAuthorize throws AuthorizationDeniedException from *inside*
    // DispatcherServlet's handler invocation (Spring MVC's own dispatch),
    // not from a filter-chain-level check - so it's caught by this
    // @RestControllerAdvice before it ever reaches ExceptionTranslationFilter
    // / SecurityConfig's accessDeniedHandler. That handler is only reachable
    // for filter-level (URL-pattern) authorization denials, which this app
    // doesn't currently have any of - every role check here is @PreAuthorize.
    // Without this handler, a role-denied request fell through to the
    // catch-all below and returned a misleading 500 instead of 403.
    @ExceptionHandler(org.springframework.security.authorization.AuthorizationDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            org.springframework.security.authorization.AuthorizationDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(403, "Forbidden", "This operation requires the ADMIN role", request.getRequestURI()));
    }

    // Confirmed by review: two concurrent POST /documents with the same
    // Idempotency-Key can both pass DocumentService's check-then-insert
    // before either commits - the DB's unique partial index correctly
    // rejects the loser, but without this handler that fell through to the
    // catch-all below as a raw 500, defeating the whole point of supplying
    // an idempotency key (the retry should transparently get the winning
    // document back, not an error). By the time this runs, the original
    // create() transaction has already rolled back, so this re-query runs
    // in its own fresh transaction/connection.
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<?> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        return documentService.findByIdempotencyKey(ex.tenantId(), ex.idempotencyKey())
                .<ResponseEntity<?>>map(doc -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .location(URI.create("/documents/" + doc.id()))
                        .body(doc))
                // Shouldn't happen - the constraint violation means a row with this
                // key exists - but don't assume that if it somehow isn't found.
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI())));
    }

    // Confirmed by review: docs/SUBMISSION.md §1.5 documents @Version as
    // giving concurrent conflicting writes "a fast, explicit 409" - without
    // this handler that claim didn't hold, since Spring Data's optimistic-
    // locking exception would have fallen through to the generic 500 below.
    // Has a real live code path even with no PUT/PATCH endpoint: two
    // concurrent DELETE requests for the same document race on @Version.
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            org.springframework.dao.OptimisticLockingFailureException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", "This document was modified concurrently, please retry",
                        request.getRequestURI()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiError.of(ex.getStatusCode().value(), ex.getStatusCode().toString(),
                        ex.getReason(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(500, "Internal Server Error", "An unexpected error occurred", request.getRequestURI()));
    }
}
