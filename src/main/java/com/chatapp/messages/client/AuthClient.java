package com.chatapp.messages.client;

/** Token verification port (NFR12): the filter (Step 22) and STOMP CONNECT (Step 39) depend on this, not on gRPC. */
public interface AuthClient {

    /** Never throws for an auth outcome: outages are values too, so no caller can forget the 503 branch. */
    AuthResult verify(String token);
}
