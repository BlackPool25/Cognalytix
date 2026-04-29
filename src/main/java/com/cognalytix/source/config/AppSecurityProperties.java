package com.cognalytix.source.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        String passwordPepper,
        /* Optional comma-separated emails for admin API access when role is ADMIN. Empty = any ADMIN user. */
        String adminAllowedEmails
) {
    public AppSecurityProperties {
        adminAllowedEmails = adminAllowedEmails == null ? "" : adminAllowedEmails;
    }
}
