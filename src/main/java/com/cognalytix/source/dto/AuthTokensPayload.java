package com.cognalytix.source.dto;

/**
 * Tokens returned to clients after register, login, refresh. Omitted on logout.
 */
public record AuthTokensPayload(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds
) {
}
