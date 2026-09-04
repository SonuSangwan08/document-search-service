package com.docsearch.config;
import com.docsearch.security.JwtAuthFilter;
import com.docsearch.service.JwtService;

import com.docsearch.dto.ApiError;
import com.docsearch.repository.TenantRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * Stateless bearer-token security: no sessions, no CSRF (nothing is
 * cookie-based), {@code /auth/login} and {@code /actuator/**} open,
 * everything else requires a verified JWT. {@link JwtAuthFilter} is
 * constructed here (not auto-registered as a {@code @Component}) so it runs
 * inside this chain, before Spring Security's own authentication filter,
 * and can populate the security context that {@code @PreAuthorize} and
 * {@code authenticated()} below rely on.
 * <p>
 * Both exception hooks below exist because Spring Security's own
 * {@code ExceptionTranslationFilter} intercepts {@code AccessDeniedException}
 * / missing-authentication cases in the filter chain <em>before</em> they
 * would ever reach {@code GlobalExceptionHandler}'s
 * {@code @RestControllerAdvice} - without these, a denied request would get
 * Spring Security's default (non-JSON, inconsistent-with-the-rest-of-the-API)
 * error response instead of the same {@link ApiError} shape every other
 * error in this service returns.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
            TenantRepository tenantRepository, ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/actuator/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler(accessDeniedHandler(objectMapper))
                        .authenticationEntryPoint(authenticationEntryPoint(objectMapper)))
                .addFilterBefore(new JwtAuthFilter(jwtService, tenantRepository, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) -> writeError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                "Forbidden", "This operation requires the ADMIN role", request.getRequestURI());
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, ex) -> writeError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized", "Authentication is required", request.getRequestURI());
    }

    private void writeError(HttpServletResponse response, ObjectMapper objectMapper, int status, String error,
            String message, String path) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), ApiError.of(status, error, message, path));
    }
}
