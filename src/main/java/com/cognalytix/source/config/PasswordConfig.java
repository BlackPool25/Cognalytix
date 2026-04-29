package com.cognalytix.source.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder(AppSecurityProperties securityProperties) {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
        String pepper = securityProperties.passwordPepper();
        return new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return bcrypt.encode(pepperedSha256Hex(rawPassword, pepper));
            }

            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return bcrypt.matches(pepperedSha256Hex(rawPassword, pepper), encodedPassword);
            }
        };
    }

    /**
     * BCrypt only accepts 72 bytes; pepper + long passwords can exceed that. Hashing (password + pepper)
     * with SHA-256 yields a fixed 64-byte hex ASCII string, then BCrypt that.
     */
    private static String pepperedSha256Hex(CharSequence rawPassword, String pepper) {
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
