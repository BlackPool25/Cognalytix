package com.cognalytix.source.dto.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Standard API error payload. For auth routes, {@link #expectedPostRequest} shows the JSON body shape clients should send.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        int status,
        String error,
        String message,
        List<FieldViolation> fieldViolations,
        ExpectedPostRequest expectedPostRequest
) {
    public record FieldViolation(String field, String message) {
    }

    public record ExpectedPostRequest(
            String httpMethod,
            String path,
            String contentType,
            java.util.Map<String, Object> bodyExample,
            String constraints
    ) {
    }
}
