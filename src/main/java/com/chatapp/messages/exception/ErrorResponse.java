package com.chatapp.messages.exception;

import java.time.Instant;

import org.springframework.http.HttpStatusCode;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * cloud-gateway's error envelope (GatewayError.java), extended with `details` (FR11 must name the offending field,
 * and the gateway's record has nowhere to put it). A client parses ONE shape on /api/messages/**, whether the body
 * came from the edge or from here (FR15).
 *
 *   {"timestamp","status","error","code","message","details","path","requestId"}
 *
 * Every field except timestamp/code/message/requestId is omitted when absent. So a non-validation error carries
 * EXACTLY the gateway's field set, and the STOMP variant (Step 38) drops the three HTTP-only fields. Step 27 adds
 * `details`, which only VALIDATION_FAILED and INVALID_PARAMETER ever populate.
 */
public record ErrorResponse(
        String timestamp,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer status,
        @JsonInclude(JsonInclude.Include.NON_NULL) String error,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object details,
        @JsonInclude(JsonInclude.Include.NON_NULL) String path,
        String requestId) {

    public static final String TOKEN_MISSING = "TOKEN_MISSING";
    public static final String TOKEN_INVALID = "TOKEN_INVALID";
    public static final String AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE";

    /** An HTTP error body. `error` is the status' reason phrase, exactly as the gateway writes it. */
    public static ErrorResponse http(HttpStatusCode status, String code, String message, String path,
                                     String requestId, Object details) {
        return new ErrorResponse(Instant.now().toString(), status.value(), reasonPhrase(status), code, message,
                details, path, requestId);
    }

    public static ErrorResponse http(HttpStatusCode status, String code, String message, String path,
                                     String requestId) {
        return http(status, code, message, path, requestId, null);
    }

    /** A STOMP ERROR frame body (Step 38): no status, no path - a frame is not a request. */
    public static ErrorResponse stomp(String code, String message, String requestId) {
        return new ErrorResponse(Instant.now().toString(), null, null, code, message, null, null, requestId);
    }

    private static String reasonPhrase(HttpStatusCode status) {
        return status instanceof org.springframework.http.HttpStatus s ? s.getReasonPhrase() : "Error";
    }
}
