package com.chatapp.messages.client;

import java.util.List;

import io.grpc.Status;

/** Every outcome of a token check is a value. Only Valid lets a request through (NFR4). */
public sealed interface AuthResult {

    /** Identity exactly as auth-service returned it. */
    record Valid(String userId, List<String> roles) implements AuthResult {
    }

    /** The token is bad or expired: 401. */
    record Invalid() implements AuthResult {
    }

    /** No trustworthy answer (outage, timeout, drift, contract break): 503. */
    record Unavailable(Status.Code code) implements AuthResult {
    }
}
