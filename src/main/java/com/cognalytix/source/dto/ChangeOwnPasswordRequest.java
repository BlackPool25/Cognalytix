package com.cognalytix.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeOwnPasswordRequest(
        @NotBlank(message = "Current password is required") String currentPassword,
        @NotBlank @Size(min = 8, max = 128, message = "New password must be 8–128 characters") String newPassword
) {
}
