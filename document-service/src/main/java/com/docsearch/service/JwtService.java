package com.docsearch.service;

import com.docsearch.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Signs and verifies the JWTs issued by {@link AuthController}. This is a
 * self-issued, shared-secret token - a prototype simplification in its own
 * right, one step up from the raw trusted header it replaces but still not
 * the full production design: a real deployment would verify tokens minted
 * by an external identity provider via JWKS, not sign them with a secret
 * this service also holds. See docs/SUBMISSION.md Security section.
 */
@Component
public class JwtService {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final Duration expiration;

    public JwtService(AppProperties properties) {
        this.signingKey = Keys.hmacShaKeyFor(properties.jwt().secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(properties.jwt().expirationMinutes());
    }

    public IssuedToken issue(String username, String tenantId, String role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiration);
        String token = Jwts.builder()
                .subject(username)
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * @throws JwtException if the token is missing, malformed, expired, or
     *                       fails signature verification
     */
    public VerifiedClaims verify(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new VerifiedClaims(claims.getSubject(), claims.get(CLAIM_TENANT_ID, String.class),
                claims.get(CLAIM_ROLE, String.class));
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    public record VerifiedClaims(String username, String tenantId, String role) {}
}
