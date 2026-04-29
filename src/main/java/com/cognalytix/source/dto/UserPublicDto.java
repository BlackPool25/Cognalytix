package com.cognalytix.source.dto;

import java.util.UUID;

/**
 * Safe user snapshot for API responses (no password or internal fields).
 */
public record UserPublicDto(UUID id, String name, String email, String role) {
}
