package com.chatapp.messages.exception;

import java.util.List;

import org.springframework.http.HttpStatus;

/** A decided domain outcome (matrix rows 6, 8, 10–17). Fixed messages only: never echoes client input. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient List<FieldViolation> details;

    private ApiException(HttpStatus status, String code, String message, List<FieldViolation> details) {
        super(message, null, false, false);   // control flow, not a bug: no stack trace
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorResponse.VALIDATION_FAILED, "Request validation failed",
                List.of(new FieldViolation(field, message)));
    }

    public static ApiException badParameter(String parameter, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_PARAMETER, "Invalid query parameter",
                List.of(new FieldViolation(parameter, message)));
    }

    public static ApiException invalidId() {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_ID,
                "Message id must be a 24-character hexadecimal id", null);
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorResponse.NOT_FOUND, message, null);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorResponse.FORBIDDEN, message, null);
    }

    public static ApiException messageDeleted() {
        return new ApiException(HttpStatus.CONFLICT, ErrorResponse.MESSAGE_DELETED, "A deleted message cannot be edited", null);
    }

    public static ApiException dependencyUnavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.DEPENDENCY_UNAVAILABLE,
                "A required service is unavailable; retry later", null);
    }

    public static ApiException dependencyTimeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, ErrorResponse.DEPENDENCY_TIMEOUT,
                "A required service did not respond in time; retry later", null);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldViolation> details() { return details; }
}
