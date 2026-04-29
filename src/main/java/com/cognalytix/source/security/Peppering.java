package com.cognalytix.source.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Applies server pepper → SHA-256 hex → downstream BCrypt. Shared by {@code PasswordEncoder} configuration.
 */
public final class Peppering {

    private Peppering() {
    }

    /**
     * BCrypt accepts at most 72 bytes; hashing (password + pepper) to SHA-256 yields a stable 64-char hex ASCII string first.
     */
    public static String pepperedSha256Hex(CharSequence rawPassword, String pepper) {
        String combined = rawPassword.toString() + pepper;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(combined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
