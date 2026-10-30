package com.chatapp.messages.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.chatapp.messages.client.MembershipClient;
import com.chatapp.messages.client.MembershipResult;
import com.chatapp.messages.client.RoomRole;
import com.chatapp.messages.security.AuthenticatedUser;
import com.chatapp.messages.security.BearerToken;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Step 40's allow-list and SD D-1's split: NotMember closes, Unavailable rejects only that subscription. */
class StompSubscribeAuthorizerTest {

    static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    static final UsernamePasswordAuthenticationToken ALICE = UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser("usr_alice", List.of("user")), new BearerToken("tok"), List.of());

    private final MembershipClient membership = mock(MembershipClient.class);
    private final MessageChannel outbound = mock(MessageChannel.class);
    private final StompSubscribeAuthorizer authorizer =
            new StompSubscribeAuthorizer(membership, outbound, JsonMapper.builder().findAndAddModules().build());

    private static Message<byte[]> frame(StompCommand command, String destination, boolean authenticated) {
        StompHeaderAccessor a = StompHeaderAccessor.create(command);
        a.setSessionId("s1");
        if (destination != null) a.setDestination(destination);
        if (command == StompCommand.SUBSCRIBE) a.setSubscriptionId("sub-0");
        if (authenticated) a.setUser(ALICE);
        a.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private static String codeOf(Runnable r) {
        try {
            r.run();
            return "accepted";
        } catch (StompRejection e) {
            return e.code();
        }
    }

    @Test
    void memberSubscriptionIsRegistered() {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.Member(RoomRole.MEMBER));
        Message<byte[]> sub = frame(StompCommand.SUBSCRIBE, "/topic/rooms." + ROOM, true);
        assertThat(authorizer.preSend(sub, null)).isSameAs(sub);
        verifyNoInteractions(outbound);
    }

    @Test
    void everyMalformedOrWildcardDestinationIsRefusedBeforeAnyMembershipCall() {
        for (String d : List.of("/topic/rooms.*", "/topic/rooms.**", "/topic/rooms.", "/topic/**", "/topic/rooms." + ROOM.toUpperCase(),
                "/topic/rooms." + ROOM + ".x", "/queue/x", "/user/queue/x", "/topic/other")) {
            assertThat(codeOf(() -> authorizer.preSend(frame(StompCommand.SUBSCRIBE, d, true), null))).as(d).isEqualTo("INVALID_DESTINATION");
        }
        verifyNoInteractions(membership);
    }

    @Test
    void subscribeWithoutASessionPrincipalIsRefused() {
        assertThat(codeOf(() -> authorizer.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms." + ROOM, false), null))).isEqualTo("TOKEN_MISSING");
    }

    @Test
    void nonMemberIsErrorAndClose() {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.NotMember());
        assertThatThrownBy(() -> authorizer.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms." + ROOM, true), null))
                .isInstanceOf(StompRejection.class).hasMessage("Room not found");
        verifyNoInteractions(outbound);
    }

    @Test
    void chatServiceOutageRejectsOnlyThatSubscriptionAndKeepsTheSession() {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.Unavailable("no instances"));

        Message<?> result = authorizer.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms." + ROOM, true), null);

        assertThat(result).as("dropped, never registered").isNull();   // no exception: no ERROR frame, no close
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Message<byte[]>> sent = (ArgumentCaptor) ArgumentCaptor.forClass(Message.class);
        verify(outbound).send(sent.capture());
        StompHeaderAccessor h = StompHeaderAccessor.wrap(sent.getValue());
        assertThat(h.getCommand()).isEqualTo(StompCommand.MESSAGE);
        assertThat(h.getSessionId()).isEqualTo("s1");
        assertThat(h.getSubscriptionId()).isEqualTo("sub-0");
        assertThat(h.getMessage()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(new String(sent.getValue().getPayload(), StandardCharsets.UTF_8))
                .contains("\"type\":\"SUBSCRIPTION_REJECTED\"").contains("\"code\":\"DEPENDENCY_UNAVAILABLE\"");
    }

    @Test
    void chatServiceTimeoutAlsoRejectsOnlyThatSubscription() {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.TimedOut());
        assertThat(authorizer.preSend(frame(StompCommand.SUBSCRIBE, "/topic/rooms." + ROOM, true), null)).isNull();
        verify(outbound).send(any());
    }

    @Test
    void sendAndOtherClientCommandsAreRefused() {
        for (StompCommand c : List.of(StompCommand.SEND, StompCommand.ACK, StompCommand.NACK, StompCommand.BEGIN,
                StompCommand.COMMIT, StompCommand.ABORT)) {
            assertThat(codeOf(() -> authorizer.preSend(frame(c, "/topic/rooms." + ROOM, true), null))).as(c.name()).isEqualTo("FRAME_NOT_ALLOWED");
        }
        verify(membership, never()).check(anyString(), any());
    }

    @Test
    void connectUnsubscribeAndDisconnectPass() {
        for (StompCommand c : List.of(StompCommand.CONNECT, StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT)) {
            Message<byte[]> m = frame(c, null, true);
            assertThat(authorizer.preSend(m, null)).as(c.name()).isSameAs(m);
        }
    }
}
