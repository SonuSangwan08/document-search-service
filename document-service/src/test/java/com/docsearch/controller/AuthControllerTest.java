package com.docsearch.controller;
import com.docsearch.dto.LoginResponse;
import com.docsearch.dto.LoginRequest;
import com.docsearch.model.UserEntity;
import com.docsearch.service.JwtService;
import com.docsearch.repository.UserRepository;

import com.docsearch.config.AppProperties;
import com.docsearch.exception.RateLimitExceededException;
import com.docsearch.service.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private RedisRateLimiter rateLimiter;

    private AuthController authController;
    private HttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties(
                new AppProperties.Elasticsearch("http://localhost:9200", "documents", 2000, 5000),
                new AppProperties.Kafka("document-index-events"),
                new AppProperties.Cache(30, 120),
                new AppProperties.RateLimit(120, 300, 10),
                new AppProperties.Jwt("test-only-not-used-in-this-test", 60));
        authController = new AuthController(userRepository, passwordEncoder, jwtService, rateLimiter, properties);

        httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.7");
        // Only read on the 401 path (ApiError body) - lenient so tests that
        // never reach a 401 don't fail on an "unnecessary stubbing".
        org.mockito.Mockito.lenient().when(httpRequest.getRequestURI()).thenReturn("/auth/login");
    }

    private UserEntity newUser(String username, String tenantId, String role, String passwordHash) {
        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setTenantId(tenantId);
        user.setRole(role);
        user.setPasswordHash(passwordHash);
        user.setCreatedAt(Instant.now());
        return user;
    }

    @Test
    void loginChecksRateLimitKeyedByRemoteAddressBeforeAnythingElse() {
        UserEntity user = newUser("admin@acme", "acme", "ADMIN", "real-hash");
        when(userRepository.findByUsername("admin@acme")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtService.issue("admin@acme", "acme", "ADMIN"))
                .thenReturn(new JwtService.IssuedToken("token-value", Instant.now().plusSeconds(3600)));

        authController.login(new LoginRequest("admin@acme", "password123"), httpRequest);

        verify(rateLimiter).checkLimit(eq("203.0.113.7"), eq("login"), eq(10));
    }

    @Test
    void loginPropagatesRateLimitExceededWithoutSwallowingIt() {
        org.mockito.Mockito.doThrow(new RateLimitExceededException(42))
                .when(rateLimiter).checkLimit(anyString(), eq("login"), eq(10));

        assertThatThrownBy(() -> authController.login(new LoginRequest("admin@acme", "password123"), httpRequest))
                .isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void loginAlwaysInvokesPasswordEncoderExactlyOnceEvenForNonexistentUser() {
        // Regression test for the timing side-channel fix: previously,
        // passwordEncoder.matches() was skipped entirely when the username
        // didn't exist, so a nonexistent-username request returned much
        // faster than a real one - letting an attacker enumerate valid
        // usernames purely from response time. It must now always run
        // exactly once, against a real hash if found or a fixed dummy hash
        // if not, so both cases pay the same BCrypt cost.
        when(userRepository.findByUsername("nobody@acme")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        ResponseEntity<?> response = authController.login(new LoginRequest("nobody@acme", "irrelevant"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(passwordEncoder, times(1)).matches(eq("irrelevant"), anyString());
    }

    @Test
    void loginRejectsWrongPasswordForExistingUser() {
        UserEntity user = newUser("admin@acme", "acme", "ADMIN", "real-hash");
        when(userRepository.findByUsername("admin@acme")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "real-hash")).thenReturn(false);

        ResponseEntity<?> response = authController.login(new LoginRequest("admin@acme", "wrong"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(passwordEncoder, times(1)).matches(eq("wrong"), anyString());
    }

    @Test
    void loginIssuesTokenForValidCredentials() {
        UserEntity user = newUser("admin@acme", "acme", "ADMIN", "real-hash");
        when(userRepository.findByUsername("admin@acme")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "real-hash")).thenReturn(true);
        when(jwtService.issue("admin@acme", "acme", "ADMIN"))
                .thenReturn(new JwtService.IssuedToken("token-value", Instant.now().plusSeconds(3600)));

        ResponseEntity<?> response = authController.login(new LoginRequest("admin@acme", "password123"), httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(LoginResponse.class);
        assertThat(((LoginResponse) response.getBody()).token()).isEqualTo("token-value");
    }
}
