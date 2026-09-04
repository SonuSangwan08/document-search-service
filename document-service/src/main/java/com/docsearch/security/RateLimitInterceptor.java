package com.docsearch.security;
import com.docsearch.service.RedisRateLimiter;

import com.docsearch.config.AppProperties;
import com.docsearch.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RedisRateLimiter rateLimiter;
    private final AppProperties properties;

    public RateLimitInterceptor(RedisRateLimiter rateLimiter, AppProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = TenantContext.get();
        boolean isSearch = request.getRequestURI().startsWith("/search");

        String bucket = isSearch ? "search" : "api";
        int limit = isSearch
                ? properties.rateLimit().searchRequestsPerMinute()
                : properties.rateLimit().defaultRequestsPerMinute();

        rateLimiter.checkLimit(tenantId, bucket, limit);
        return true;
    }
}
