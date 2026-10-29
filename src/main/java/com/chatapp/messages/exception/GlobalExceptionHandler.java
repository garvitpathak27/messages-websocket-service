package com.chatapp.messages.exception;

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.chatapp.messages.logging.RequestIds;
import com.chatapp.messages.repository.InvalidCursorException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One owner per error class (see "The one thing to understand"). Spring picks the handler by exception-type
 * distance, not declaration order; the catch-all only receives what nothing more specific — here or in
 * ResponseEntityExceptionHandler's own list — claims.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // --- Domain outcomes (matrix rows 6, 8, 10-17) ---
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException e, HttpServletRequest req, HttpServletResponse res) {
        return envelope(req, res, null, e.status(), e.code(), e.getMessage(), e.details());
    }

    // --- Step 15's opaque cursor failure (row 9). Fixed message; never the decoded content. ---
    @ExceptionHandler(InvalidCursorException.class)
    ResponseEntity<Object> invalidCursor(InvalidCursorException e, HttpServletRequest req, HttpServletResponse res) {
        return envelope(req, res, null, HttpStatus.BAD_REQUEST, ErrorResponse.INVALID_CURSOR, InvalidCursorException.MESSAGE, null);
    }

    // --- MongoDB unreachable / timed out (row 18, spec §14) ---
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<Object> datastore(DataAccessResourceFailureException e, HttpServletRequest req, HttpServletResponse res) {
        log.warn("MongoDB unavailable for request {}: {}", RequestIds.get(req, res), e.getMostSpecificCause().toString());
        return envelope(req, res, null, HttpStatus.SERVICE_UNAVAILABLE, ErrorResponse.DEPENDENCY_UNAVAILABLE,
                "A required service is unavailable; retry later", null);
    }

    // --- Spring Security owns these: rethrow so ExceptionTranslationFilter (and Step 21's entry point) handles them ---
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    void securityExceptionsBelongToSpringSecurity(RuntimeException e) {
        throw e;
    }

    // --- Last resort (row 21): a bug. Generic message to the client, full detail in the log. ---
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e, HttpServletRequest req, HttpServletResponse res) {
        log.error("unhandled exception for request {}", RequestIds.get(req, res), e);
        return envelope(req, res, null, HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse.INTERNAL_ERROR, "Internal server error", null);
    }

    // --- Spring MVC's own exceptions (rows 5-7, 19, 20) all funnel through this single hook ---
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        ServletWebRequest web = (ServletWebRequest) request;
        String code = codeFor(ex, status);
        if (status.is5xxServerError()) {
            log.error("framework error {} for request {}", status.value(), RequestIds.get(web.getRequest(), web.getResponse()), ex);
        } else {
            log.debug("request rejected with {} {}: {}", status.value(), code, ex.getClass().getSimpleName());
        }
        Object details = ex instanceof MethodArgumentNotValidException manv ? violations(manv) : null;
        return envelope(web.getRequest(), web.getResponse(), headers, status, code, messageFor(code), details);
    }

    private static String codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) return ErrorResponse.VALIDATION_FAILED;
        if (ex instanceof HttpMessageNotReadableException) return ErrorResponse.MALFORMED_JSON;
        if (ex instanceof NoResourceFoundException) return ErrorResponse.NOT_FOUND;
        if (ex instanceof HttpRequestMethodNotSupportedException) return ErrorResponse.METHOD_NOT_ALLOWED;
        if (ex instanceof HttpMediaTypeNotSupportedException) return ErrorResponse.UNSUPPORTED_MEDIA_TYPE;
        if (ex instanceof MissingServletRequestParameterException || ex instanceof TypeMismatchException) return ErrorResponse.INVALID_PARAMETER;
        return status.is5xxServerError() ? ErrorResponse.INTERNAL_ERROR : ErrorResponse.REQUEST_REJECTED;
    }

    /** Fixed text per code: framework exception messages quote the request (e.g. the JSON parse snippet). */
    private static String messageFor(String code) {
        return switch (code) {
            case ErrorResponse.VALIDATION_FAILED -> "Request validation failed";
            case ErrorResponse.MALFORMED_JSON -> "Request body is not valid JSON";
            case ErrorResponse.NOT_FOUND -> "Not found";
            case ErrorResponse.METHOD_NOT_ALLOWED -> "Method not allowed";
            case ErrorResponse.UNSUPPORTED_MEDIA_TYPE -> "Content-Type must be application/json";
            case ErrorResponse.INVALID_PARAMETER -> "Invalid query parameter";
            case ErrorResponse.INTERNAL_ERROR -> "Internal server error";
            default -> "Request rejected";
        };
    }

    private static List<FieldViolation> violations(MethodArgumentNotValidException e) {
        return e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage()))   // never fe.getRejectedValue()
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
    }

    private static ResponseEntity<Object> envelope(HttpServletRequest req, HttpServletResponse res, @Nullable HttpHeaders headers,
                                                   HttpStatusCode status, String code, String message, Object details) {
        String requestId = RequestIds.get(req, res);
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.http(status, code, message, req.getRequestURI(), requestId, details));
    }
}
