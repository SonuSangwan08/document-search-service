package com.docsearch.exception;

/**
 * Thrown when two concurrent {@code POST /documents} requests race on the
 * same tenant + {@code Idempotency-Key}: both pass the check-then-insert's
 * initial lookup before either has committed, so the DB's unique partial
 * index (not this code) is what actually rejects the loser. Confirmed by
 * review that without this, the loser's request fell through to
 * {@code GlobalExceptionHandler}'s generic catch-all and returned a raw 500
 * instead of behaving idempotently - which defeats the entire point of
 * supplying an idempotency key. {@link com.docsearch.exception.GlobalExceptionHandler}
 * catches this and re-queries for the winning document in a fresh
 * transaction (the one that hit the constraint violation is already
 * rolled back by the time this is thrown).
 */
public class IdempotencyConflictException extends RuntimeException {

    private final String tenantId;
    private final String idempotencyKey;

    public IdempotencyConflictException(String tenantId, String idempotencyKey) {
        super("Idempotency key '" + idempotencyKey + "' conflicted for tenant '" + tenantId + "'");
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
    }

    public String tenantId() {
        return tenantId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
