package com.chatapp.messages.security;

import java.util.Objects;

/**
 * The caller's raw bearer token, kept ONLY so it can be forwarded to chat-service (D2) and, from Step 39, carried from
 * STOMP CONNECT to SUBSCRIBE. NFR9: never log it, never put it in an exception message. toString() is redacted so an
 * accidental log statement or debugger string prints nothing useful.
 */
public final class BearerToken {

    private final String value;

    public BearerToken(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** For an outgoing Authorization header and nothing else. */
    public String authorizationHeader() {
        return "Bearer " + value;
    }

    @Override
    public String toString() {
        return "BearerToken[REDACTED]";
    }
}
