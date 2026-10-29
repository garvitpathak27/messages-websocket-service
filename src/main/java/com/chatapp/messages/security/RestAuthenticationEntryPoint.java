package com.chatapp.messages.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.chatapp.messages.exception.ErrorResponse;
import com.chatapp.messages.exception.ErrorResponses;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Every 401 in this service, in the platform envelope. WWW-Authenticate per RFC 6750. */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper mapper;

    public RestAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) throws IOException {
        boolean rejected = ex instanceof TokenRejectedException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, rejected ? "Bearer error=\"invalid_token\"" : "Bearer");
        ErrorResponses.write(request, response, mapper, HttpStatus.UNAUTHORIZED,
                rejected ? ErrorResponse.TOKEN_INVALID : ErrorResponse.TOKEN_MISSING,
                rejected ? ex.getMessage() : "Authentication required");
    }
}
