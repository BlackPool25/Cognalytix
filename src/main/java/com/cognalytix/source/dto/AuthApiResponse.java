package com.cognalytix.source.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Standard auth outcome: human-readable message plus optional session material.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthApiResponse(
        String message,
        AuthTokensPayload tokens,
        UserPublicDto user
) {
}
