package com.chatapp.messages.security;

import java.security.Principal;
import java.util.List;

/** The caller, exactly as auth-service described them. getName() is the user id (STOMP user destinations, Step 39). */
public record AuthenticatedUser(String userId, List<String> roles) implements Principal {

    @Override
    public String getName() {
        return userId;
    }
}
