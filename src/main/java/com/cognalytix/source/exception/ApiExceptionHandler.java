package com.cognalytix.source.exception;

import com.cognalytix.source.dto.error.ApiErrorResponse;
import com.cognalytix.source.dto.error.AuthPostBodyHints;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

/**
 * Runs before Boot's {@code ProblemDetailsExceptionHandler} so MVC errors (empty body, invalid JSON,
 * validation) return {@link ApiErrorResponse} with POST examples instead of the default error JSON.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @org.springframework.web.bind.annotation.ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "forbidden",
                "You do not have permission for this operation.",
                List.of(),
                AuthPostBodyHints.forRequestPath(requestUri(request))
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String uri = requestUri(request);
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "invalid_body",
                describeUnreadable(ex),
                List.of(),
                AuthPostBodyHints.forRequestPath(uri)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        String uri = requestUri(request);
        List<ApiErrorResponse.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> new ApiErrorResponse.FieldViolation(err.getField(), err.getDefaultMessage()))
                .toList();

        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "validation_failed",
                "Request body failed validation. Send JSON matching expectedPostRequest.",
                violations,
                AuthPostBodyHints.forRequestPath(uri)
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @Override
    protected ResponseEntity<Object> handleErrorResponseException(
            ErrorResponseException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        if (ex instanceof ResponseStatusException rse) {
            int statusCode = rse.getStatusCode().value();
            String message = rse.getReason() != null ? rse.getReason() : "Error";
            ApiErrorResponse body = new ApiErrorResponse(
                    statusCode,
                    "request_failed",
                    message,
                    List.of(),
                    AuthPostBodyHints.forRequestPath(requestUri(request))
            );
            return ResponseEntity.status(rse.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }
        return super.handleErrorResponseException(ex, headers, status, request);
    }

    private static String requestUri(WebRequest request) {
        if (request instanceof ServletWebRequest sw) {
            return sw.getRequest().getRequestURI();
        }
        return null;
    }

    private static String describeUnreadable(HttpMessageNotReadableException ex) {
        String msg = ex.getMessage();
        if (msg != null && msg.toLowerCase().contains("required request body is missing")) {
            return "Request body is required and cannot be empty. Send JSON with Content-Type: application/json.";
        }
        return "Body must be valid JSON. Use UTF-8 and Content-Type: application/json.";
    }
}
