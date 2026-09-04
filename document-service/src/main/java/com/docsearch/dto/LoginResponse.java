package com.docsearch.dto;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, String tenantId, String role) {
}
