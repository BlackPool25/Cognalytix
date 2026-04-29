package com.cognalytix.source.dto;

/**
 * Result of an admin action on a user account.
 */
public record AdminUserActionResponse(String message, UserPublicDto user) {
}
