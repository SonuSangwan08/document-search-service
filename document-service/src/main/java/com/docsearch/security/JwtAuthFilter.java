package com.docsearch.security;
import com.docsearch.service.JwtService;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.docsearch.dto.ApiError;
import com.docsearch.security.TenantContext;
import com.docsearch.model.TenantEntity;
import com.docsearch.repository.TenantRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The single choke point for authentication: every request except
 * /auth/login and /actuator/** must carry {@code Authorization: Bearer
 * <token>} naming an active tenant. Unlike the trusted-header model this
 * replaces (see git history / docs/SUBMISSION.md - formerly TenantFilter +
 * RoleFilter), tenantId and role now come from a JWT that was signed by
 * this service at login time and is cryptographically verified here, not
 * supplied as-is by the client on every call.
 * <p>
 * Registered manually in {@link SecurityConfig} via
 * {@code addFilterBefore(...)} rather than as an auto-registered
 * {@code @Component} filter, so it runs inside the Spring Security chain
 * and can populate {@link SecurityContextHolder} before authorization
 * checks (`@PreAuthorize`, `authenticated()`) evaluate.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PREFIXES = Set.of("/actuator", "/error", "/auth/login");

    private final JwtService jwtService;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    // Same 30s active-tenant staleness trade-off as the filter this replaces:
    // a token can outlive its tenant being suspended by up to this long.
    private final Cache<String, Boolean> activeTenantCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    public JwtAuthFilter(JwtService jwtService, TenantRepository tenantRepository, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.tenantRepository = tenantRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, "Missing Token", "Required 'Authorization: Bearer <token>' header was not provided",
                    request.getRequestURI());
            return;
        }

        JwtService.VerifiedClaims claims;
        try {
            claims = jwtService.verify(header.substring("Bearer ".length()));
        } catch (JwtException ex) {
            writeError(response, "Invalid Token", "Token is missing, malformed, expired, or has an invalid signature",
                    request.getRequestURI());
            return;
        }

        boolean active = activeTenantCache.get(claims.tenantId(),
                id -> tenantRepository.existsByIdAndStatus(id, TenantEntity.TenantStatus.ACTIVE));
        if (!active) {
            writeError(response, "Unknown Tenant", "Tenant '" + claims.tenantId() + "' does not exist or is not active",
                    request.getRequestURI());
            return;
        }

        try {
            TenantContext.set(claims.tenantId());
            MDC.put("tenantId", claims.tenantId());
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    claims.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + claims.role()))));
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove("tenantId");
            SecurityContextHolder.clearContext();
        }
    }

    private void writeError(HttpServletResponse response, String error, String message, String path) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiError.of(401, error, message, path));
    }
}
