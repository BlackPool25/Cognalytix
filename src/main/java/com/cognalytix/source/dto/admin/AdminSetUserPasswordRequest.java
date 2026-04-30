package com.cognalytix.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminSetUserPasswordRequest(
        @NotBlank @Size(min = 8, max = 128, message = "Password must be 8–128 characters") String newPassword
) {
}
