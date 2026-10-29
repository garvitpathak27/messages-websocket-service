package com.chatapp.messages.security;

import org.springframework.security.core.AuthenticationException;

/** A presented credential was rejected. Messages are fixed strings chosen by the filter — never the token. */
public class TokenRejectedException extends AuthenticationException {

    public TokenRejectedException(String fixedMessage) {
        super(fixedMessage);
    }
}
