package com.chatapp.messages.security;

import org.springframework.security.core.Authentication;

/** Who is calling and the credential to forward on their behalf, read explicitly from the Authentication. */
public record Caller(AuthenticatedUser user, BearerToken token) {

    public static Caller from(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
                || !(authentication.getCredentials() instanceof BearerToken token)) {
            throw new IllegalStateException("no authenticated caller with a forwardable token on this request");
        }
        return new Caller(user, token);
    }
}
