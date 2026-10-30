package com.chatapp.messages.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;

import com.chatapp.messages.client.AuthClient;
import com.chatapp.messages.client.AuthResult;
import com.chatapp.messages.security.Caller;

import io.grpc.Status;

/** Step 39: CONNECT is authenticated once, through the same AuthClient as REST; every other frame passes untouched. */
class StompAuthChannelInterceptorTest {

    private final AuthClient auth = mock(AuthClient.class);
    private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(auth);

    private static Message<byte[]> connect(String... authorization) {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.CONNECT);
        for (String v : authorization) a.addNativeHeader("Authorization", v);
        a.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private String codeOf(Message<byte[]> m) {
        try {
            interceptor.preSend(m, null);
            return "accepted";
        } catch (StompRejection e) {
            return e.code();
        }
    }

    @Test
    void validTokenSetsThePrincipalWithTheForwardableToken() {
        when(auth.verify("good")).thenReturn(new AuthResult.Valid("usr_alice", List.of("user")));
        Message<byte[]> m = connect("Bearer good");

        interceptor.preSend(m, null);

        StompHeaderAccessor a = MessageHeaderAccessor.getAccessor(m, StompHeaderAccessor.class);
        assertThat(a.getUser()).isInstanceOf(Authentication.class);
        assertThat(a.getUser().getName()).isEqualTo("usr_alice");                       // STOMP user-name
        assertThat(Caller.from((Authentication) a.getUser()).token().authorizationHeader()).isEqualTo("Bearer good");
    }

    @Test
    void everyRejectionHasItsCode() {
        when(auth.verify("bad")).thenReturn(new AuthResult.Invalid());
        when(auth.verify("down")).thenReturn(new AuthResult.Unavailable(Status.Code.UNAVAILABLE));
        assertThat(codeOf(connect())).isEqualTo("TOKEN_MISSING");
        assertThat(codeOf(connect("Bearer a", "Bearer b"))).isEqualTo("TOKEN_INVALID");
        assertThat(codeOf(connect("Basic YWxpY2U6eA=="))).isEqualTo("TOKEN_INVALID");
        assertThat(codeOf(connect("Bearer two words"))).isEqualTo("TOKEN_INVALID");
        assertThat(codeOf(connect("Bearer bad"))).isEqualTo("TOKEN_INVALID");
        assertThat(codeOf(connect("Bearer down"))).isEqualTo("AUTH_UNAVAILABLE");
    }

    @Test
    void nonConnectFramesAreNotAuthenticatedAgain() {
        StompHeaderAccessor a = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        a.setLeaveMutable(true);
        Message<byte[]> m = MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
        assertThat(interceptor.preSend(m, null)).isSameAs(m);
        verifyNoInteractions(auth);
    }
}
