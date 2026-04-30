package com.cognalytix.source.dto.admin;

import com.cognalytix.source.dto.user.UserPublicDto;

/**
 * Result of an admin action on a user account.
 */
public record AdminUserActionResponse(String message, UserPublicDto user) {
}
