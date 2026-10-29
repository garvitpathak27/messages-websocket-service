package com.chatapp.messages.exception;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.chatapp.messages.logging.RequestIds;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Writes the envelope from places controller advice cannot reach (filters, entry points). */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, ObjectMapper mapper,
                             HttpStatus status, String code, String message) throws IOException {
        String requestId = RequestIds.get(request, response);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(),
                ErrorResponse.http(status, code, message, request.getRequestURI(), requestId));
    }
}
