package com.cognalytix.source.dto.error;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AuthPostBodyHints {

    private static final String JSON = "application/json";

    private AuthPostBodyHints() {
    }

    public static ApiErrorResponse.ExpectedPostRequest forRequestPath(String requestUri) {
        if (requestUri == null) {
            return null;
        }
        int q = requestUri.indexOf('?');
        String path = q >= 0 ? requestUri.substring(0, q) : requestUri;
        if (path.endsWith("/register")) {
            return register();
        }
        if (path.endsWith("/login")) {
            return login();
        }
        if (path.endsWith("/logout")) {
            return refreshStyle("/api/auth/logout", "Same body as /api/auth/refresh; revokes the refresh token.");
        }
        if (path.endsWith("/refresh")) {
            return refreshStyle("/api/auth/refresh", "Exchange a valid refresh token for a new access token.");
        }
        return null;
    }

    private static ApiErrorResponse.ExpectedPostRequest register() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Jane Doe");
        body.put("email", "jane@example.com");
        body.put("password", "minimum8Chars");
        return new ApiErrorResponse.ExpectedPostRequest(
                "POST",
                "/api/auth/register",
                JSON,
                body,
                "name: required, max 100. email: required, valid email. password: required, 8–128 chars."
        );
    }

    private static ApiErrorResponse.ExpectedPostRequest login() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", "jane@example.com");
        body.put("password", "yourPassword");
        return new ApiErrorResponse.ExpectedPostRequest(
                "POST",
                "/api/auth/login",
                JSON,
                body,
                "email: required, valid email. password: required."
        );
    }

    private static ApiErrorResponse.ExpectedPostRequest refreshStyle(String path, String constraints) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("refreshToken", "<opaque token string from login or register>");
        return new ApiErrorResponse.ExpectedPostRequest(
                "POST",
                path,
                JSON,
                body,
                constraints
        );
    }
}
