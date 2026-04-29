package com.cognalytix.source.security;

import com.cognalytix.source.dto.error.ApiErrorResponse;
import com.cognalytix.source.dto.error.AuthPostBodyHints;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.util.Collections;

/**
 * Writes {@link ApiErrorResponse} JSON for servlet filters and security entry points.
 */
public final class ApiErrorResponseWriter {

    private ApiErrorResponseWriter() {
    }

    public static void write(
            HttpServletResponse response,
            ObjectMapper mapper,
            int httpStatus,
            String errorCode,
            String message,
            HttpServletRequest request
    ) throws IOException {
        write(response, mapper, httpStatus, errorCode, message, uri(request));
    }

    public static void write(
            HttpServletResponse response,
            ObjectMapper mapper,
            int httpStatus,
            String errorCode,
            String message,
            String requestUri
    ) throws IOException {
        ApiErrorResponse body = new ApiErrorResponse(
                httpStatus,
                errorCode,
                message,
                Collections.emptyList(),
                hintFor(uriForHints(requestUri), requestUri)
        );
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), body);
    }

    private static String uriForHints(String path) {
        if (path == null) {
            return null;
        }
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private static ApiErrorResponse.ExpectedPostRequest hintFor(String pathSansQuery, String fullUri) {
        if (pathSansQuery != null && pathSansQuery.contains("/auth/")) {
            return AuthPostBodyHints.forRequestPath(fullUri != null ? fullUri : pathSansQuery);
        }
        return null;
    }

    private static String uri(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String q = request.getQueryString();
        String p = request.getRequestURI();
        return q == null ? p : p + "?" + q;
    }
}
