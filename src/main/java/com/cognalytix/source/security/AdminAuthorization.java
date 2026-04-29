package com.cognalytix.source.security;

import com.cognalytix.source.config.AppSecurityProperties;
import com.cognalytix.source.domain.user.Role;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin routes: ADMIN role plus optional comma-separated {@code app.security.admin-allowed-emails} allow-list.
 */
@Component("adminAuth")
public class AdminAuthorization {

    private final Set<String> allowedLowercaseEmails;

    public AdminAuthorization(AppSecurityProperties appSecurityProperties) {
        String raw = appSecurityProperties.adminAllowedEmails();
        if (raw == null || raw.isBlank()) {
            this.allowedLowercaseEmails = Set.of();
        } else {
            this.allowedLowercaseEmails = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }
    }

    /**
     * Use with {@code @PreAuthorize("@adminAuth.allow(authentication)")}.
     */
    public boolean allow(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUserPrincipal principal)) {
            return false;
        }
        if (principal.getRole() != Role.ADMIN) {
            return false;
        }
        if (allowedLowercaseEmails.isEmpty()) {
            return true;
        }
        return allowedLowercaseEmails.contains(principal.getEmail().toLowerCase(Locale.ROOT));
    }
}
