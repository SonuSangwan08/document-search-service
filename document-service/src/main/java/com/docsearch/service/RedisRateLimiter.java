package com.docsearch.service;
import com.docsearch.exception.RateLimitExceededException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fixed-window per-tenant rate limiter backed by Redis so the limit is
 * enforced consistently across every app instance, not per-process. INCR +
 * conditional EXPIRE run as a single Lua script so a crash between the two
 * calls can never leave a counter key without a TTL (which would otherwise
 * permanently lock a tenant out).
 * <p>
 * A fixed window allows a burst of up to 2x the limit at window boundaries;
 * that trade-off is accepted here for simplicity - see
 * docs/PRODUCTION_READINESS.md for the sliding-window-log alternative.
 */
@Component
@Slf4j
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> INCR_AND_EXPIRE = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if tonumber(current) == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @param bucket        a namespace for the limit, e.g. "api" or "search",
     *                      so different endpoint classes get independent budgets
     * @param limitPerMinute requests allowed per rolling calendar minute
     * @throws RateLimitExceededException if the tenant is over budget
     */
    public void checkLimit(String tenantId, String bucket, int limitPerMinute) {
        long windowEpochMinute = Instant.now().getEpochSecond() / 60;
        String key = "ratelimit:%s:%s:%d".formatted(bucket, tenantId, windowEpochMinute);

        Long count;
        try {
            count = redisTemplate.execute(INCR_AND_EXPIRE, List.of(key), String.valueOf(Duration.ofMinutes(1).toSeconds()));
        } catch (Exception ex) {
            // Fail open, not closed - confirmed live (docs/SUBMISSION.md §2
            // Resilience) that a Redis outage otherwise took the entire API
            // down with a 500 on every request, including writes, since this
            // check runs before every endpoint. Rate limiting is a fairness/
            // abuse control; losing it temporarily during a cache outage is a
            // far smaller problem than losing all availability over it.
            log.warn("Rate limiter unavailable (tenant={} bucket={}), failing open for this request: {}",
                    tenantId, bucket, ex.getMessage());
            return;
        }

        if (count != null && count > limitPerMinute) {
            long retryAfter = 60 - (Instant.now().getEpochSecond() % 60);
            log.warn("Rate limit exceeded for tenant={} bucket={} count={} limit={}", tenantId, bucket, count, limitPerMinute);
            throw new RateLimitExceededException(retryAfter);
        }
    }
}
