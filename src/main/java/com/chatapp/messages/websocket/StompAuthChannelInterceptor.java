package com.chatapp.messages.websocket;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import com.chatapp.messages.client.AuthClient;
import com.chatapp.messages.client.AuthResult;
import com.chatapp.messages.exception.ErrorResponse;
import com.chatapp.messages.security.AuthenticatedUser;
import com.chatapp.messages.security.BearerToken;

/**
 * CONNECT authentication (FR10 for STOMP). Verified ONCE per session — see Step 39's stated security cost.
 * Never logs the token (NFR9).
 */
@Component
@Order(1)
public class StompAuthChannelInterceptor implements InboundStompInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    /** Same pattern as BearerTokenAuthenticationFilter (Step 22). */
    private static final Pattern BEARER = Pattern.compile("^Bearer +([A-Za-z0-9._~+/-]+=*) *$", Pattern.CASE_INSENSITIVE);

    private final AuthClient authClient;

    public StompAuthChannelInterceptor(AuthClient authClient) {
        this.authClient = authClient;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() != SimpMessageType.CONNECT) {
            return message;
        }
        List<String> values = accessor.getNativeHeader(HttpHeaders.AUTHORIZATION);   // List<String>: STOMP allows repeats
        if (values == null || values.isEmpty()) {
            throw new StompRejection(ErrorResponse.TOKEN_MISSING, "Authorization header required in CONNECT");
        }
        if (values.size() > 1) {
            throw new StompRejection(ErrorResponse.TOKEN_INVALID, "Exactly one Authorization header is allowed");
        }
        Matcher m = BEARER.matcher(values.get(0));
        if (!m.matches()) {
            throw new StompRejection(ErrorResponse.TOKEN_INVALID, "Authorization header must be 'Bearer <token>'");
        }
        switch (authClient.verify(m.group(1))) {
            case AuthResult.Valid v -> {
                AuthenticatedUser user = new AuthenticatedUser(v.userId(), v.roles());
                var authorities = v.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
                // setUser on the (mutable) CONNECT accessor: Spring attaches this principal to every later frame of the session
                accessor.setUser(UsernamePasswordAuthenticationToken.authenticated(user, new BearerToken(m.group(1)), authorities));
                log.debug("STOMP session {} authenticated userId={}", accessor.getSessionId(), v.userId());
            }
            case AuthResult.Invalid i -> throw new StompRejection(ErrorResponse.TOKEN_INVALID, "Token is invalid or expired");
            case AuthResult.Unavailable u -> throw new StompRejection(ErrorResponse.AUTH_UNAVAILABLE, "Authentication service unavailable");
        }
        return message;
    }
}
