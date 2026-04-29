package com.cognalytix.source.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a stable HMAC-SHA256 hex digest of the opaque refresh token for database storage (RTR-safe lookup).
 */
public final class RefreshTokenHasher {

    private static final String HMAC_ALG = "HmacSHA256";

    private final byte[] keyBytes;

    public RefreshTokenHasher(String refreshStorageSecret) {
        if (refreshStorageSecret == null || refreshStorageSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "Configure app.jwt.refresh-storage-secret (env REFRESH_STORAGE_SECRET) with a long random value."
            );
        }
        this.keyBytes = hmacKeyMaterial(refreshStorageSecret);
    }

    public String hashOpaqueToken(String plainRefreshToken) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALG));
            byte[] raw = mac.doFinal(plainRefreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static byte[] hmacKeyMaterial(String secret) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length >= 32) {
            return raw;
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
