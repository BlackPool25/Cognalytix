package com.cognalytix.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangeServerPepperRequest(
        @NotBlank(message = "Admin password is required") String adminPassword,
        @NotBlank @Size(min = 32, max = 8192, message = "New pepper must be at least 32 characters") String newPepper
) {
}
