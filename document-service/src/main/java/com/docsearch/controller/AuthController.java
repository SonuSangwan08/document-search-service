package com.docsearch.controller;
import com.docsearch.dto.LoginResponse;
import com.docsearch.model.UserEntity;
import com.docsearch.dto.LoginRequest;
import com.docsearch.service.JwtService;
import com.docsearch.repository.UserRepository;

import com.docsearch.config.AppProperties;
import com.docsearch.dto.ApiError;
import com.docsearch.service.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Exchanges a username/password for a signed JWT. This is the only endpoint
 * a client can reach without a token (see JwtAuthFilter/SecurityConfig) -
 * everything else requires {@code Authorization: Bearer <token>}, with the
 * tenant id and role coming from verified claims inside that token rather
 * than a trusted request header.
 */
@RestController
public class AuthController {

    // A syntactically valid, cost-matched BCrypt hash that is not, and has
    // never been, any real account's password hash - used only so a
    // nonexistent-username request pays the same BCrypt cost as a real one.
    // See the timing-safety comment in login() below.
    private static final String DUMMY_PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RedisRateLimiter rateLimiter;
    private final AppProperties properties;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
            RedisRateLimiter rateLimiter, AppProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // Keyed by remote address, not tenant (unknown pre-auth) and not any
        // X-Forwarded-For-style header - trusting a client-supplied header
        // for the rate-limit key here, with no trusted-proxy layer in front
        // to strip/validate it, would let an attacker just send a new
        // "IP" on every request and bypass the limit entirely.
        rateLimiter.checkLimit(httpRequest.getRemoteAddr(), "login", properties.rateLimit().loginRequestsPerMinute());

        Optional<UserEntity> user = userRepository.findByUsername(request.username());

        // Timing-safe by construction, not just by response shape: BCrypt is
        // deliberately slow, and it was previously only invoked when the
        // username existed, so a nonexistent-username request returned
        // near-instantly while a real one paid BCrypt's cost - letting an
        // attacker enumerate valid usernames purely from response time even
        // though the response body was already identical either way. Now
        // matches() always runs exactly once, against the real hash if the
        // user exists or a fixed dummy hash (same cost factor) if not.
        String hashToCheck = user.map(UserEntity::getPasswordHash).orElse(DUMMY_PASSWORD_HASH);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

        if (user.isEmpty() || !passwordMatches) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiError.of(401, "Unauthorized", "Invalid username or password",
                            httpRequest.getRequestURI()));
        }

        UserEntity account = user.get();
        JwtService.IssuedToken issued = jwtService.issue(account.getUsername(), account.getTenantId(), account.getRole());
        return ResponseEntity.ok(new LoginResponse(issued.token(), issued.expiresAt(), account.getTenantId(), account.getRole()));
    }
}
