package com.cognalytix.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpiryMs,
        int refreshTokenExpiryDays
) {
}
