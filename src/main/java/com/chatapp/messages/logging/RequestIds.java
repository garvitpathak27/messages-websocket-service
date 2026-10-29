package com.chatapp.messages.logging;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One X-Request-ID per request (same header name as chat-service's CorrelationIdMiddleware). The gateway sends none,
 * so this service always generates its own. Step 50's RequestIdFilter calls get() first on every request and adds MDC.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-ID";
    private static final String ATTRIBUTE = RequestIds.class.getName();

    private RequestIds() {
    }

    public static String get(HttpServletRequest request, HttpServletResponse response) {
        if (request.getAttribute(ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        return id;
    }
}
