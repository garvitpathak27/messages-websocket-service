package com.chatapp.messages.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.chatapp.messages.client.AuthClient;
import com.chatapp.messages.client.AuthResult;
import com.chatapp.messages.client.MembershipClient;
import com.chatapp.messages.client.MembershipResult;
import com.chatapp.messages.client.RoomRole;
import com.chatapp.messages.config.SecurityConfig;
import com.chatapp.messages.messaging.MessageEventPublisher;
import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.MessageType;
import com.chatapp.messages.repository.MessageQueryRepository;
import com.chatapp.messages.repository.MessageRepository;
import com.chatapp.messages.security.RestAuthenticationEntryPoint;
import com.chatapp.messages.service.ContentPolicy;
import com.chatapp.messages.service.MessageService;

import io.grpc.Status;

/**
 * The real security chain (SecurityConfig builds the bearer filter; @WebMvcTest would not scan it), the real
 * GlobalExceptionHandler (picked up as @ControllerAdvice), real MessageService/ContentPolicy, mocked edges.
 */
@WebMvcTest(MessageController.class)
@Import({SecurityConfig.class, RestAuthenticationEntryPoint.class, MessageService.class, ContentPolicy.class,
        MessageControllerWebTest.PinnedClock.class})
class MessageControllerWebTest {

    @TestConfiguration
    static class PinnedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(T, ZoneOffset.UTC);
        }
    }

    static final Instant T = Instant.parse("2026-10-20T10:15:30.123Z");
    static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    static final String CID = "c6f1d1f2-6b0e-4d3e-9a59-0d7a2c3e1b44";
    static final String ID = "6716c3f2a1b2c3d4e5f60718";
    static final String ALICE = "usr_alice";
    static final String BOB = "usr_bob";

    @Autowired MockMvc mvc;
    @MockitoBean AuthClient auth;
    @MockitoBean MembershipClient membership;
    @MockitoBean MessageRepository messages;
    @MockitoBean MessageQueryRepository queries;
    @MockitoBean MongoTemplate mongo;
    @MockitoBean MessageEventPublisher events;

    @BeforeEach
    void identities() {
        when(auth.verify("good")).thenReturn(new AuthResult.Valid(ALICE, List.of("user")));
        when(auth.verify("bad")).thenReturn(new AuthResult.Invalid());
        when(auth.verify("down")).thenReturn(new AuthResult.Unavailable(Status.Code.UNAVAILABLE));
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.Member(RoomRole.MEMBER));
        when(messages.insert(any(Message.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
    }

    // ---------- helpers ----------
    static Message withId(Message m) {
        return new Message(ID, m.getRoomId(), m.getSenderId(), m.getContent(), m.getType(), m.getClientMessageId(), m.getCreatedAt(), null, null, null);
    }

    static Message stored(String sender, boolean deleted) {
        return new Message(ID, ROOM, sender, "hello", MessageType.TEXT, CID, T, null, deleted ? T : null, deleted ? sender : null);
    }

    static String sendBody(String content) {
        return "{\"roomId\":\"" + ROOM + "\",\"content\":\"" + content + "\",\"clientMessageId\":\"" + CID + "\"}";
    }

    /** An error body minus the two fields that legitimately differ per request. */
    static String withoutPerRequestFields(String json) {
        return json.replaceAll("\"timestamp\":\"[^\"]*\"", "").replaceAll("\"requestId\":\"[^\"]*\"", "");
    }

    ResultActions send(String token, String json) throws Exception {
        return mvc.perform(post("/api/messages").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    // ---------- the real filter is in the chain ----------
    @Test
    void noTokenIs401InThePlatformEnvelope() throws Exception {
        mvc.perform(get("/api/messages").param("roomId", ROOM))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("TOKEN_MISSING"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(membership, messages);
    }

    @Test
    void validBearerTokenReachesTheControllerOnlyThroughTheRealFilter() throws Exception {
        send("good", "{\"roomId\":\"" + ROOM + "\",\"content\":\"  hi  \",\"clientMessageId\":\"" + CID + "\",\"senderId\":\"usr_attacker\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.senderId").value(ALICE))
                .andExpect(jsonPath("$.content").value("hi"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-20T10:15:30.123Z"))
                .andExpect(jsonPath("$.deleted").value(false));
        verify(auth, times(1)).verify("good");   // once: the filter is not registered twice (Step 22)
        verify(events).created(any(Message.class));
    }

    @Test
    void invalidTokenIs401() throws Exception {
        send("bad", sendBody("hi")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void authServiceDownIs503() throws Exception {
        send("down", sendBody("hi")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        verifyNoInteractions(membership, messages);
    }

    // ---------- validation (rows 5-7) ----------
    @Test
    void blankContentIs400WithFieldDetails() throws Exception {
        send("good", sendBody("   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0].field").value("content"))
                .andExpect(content().string(not(containsString("rejected"))));
        verifyNoInteractions(membership);
    }

    @Test
    void contentOverTheConfiguredMaxIs400() throws Exception {
        send("good", sendBody("x".repeat(4001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].message").value("must be at most 4000 characters"));
    }

    @Test
    void roomIdNotAUuidIs400() throws Exception {
        send("good", "{\"roomId\":\"42\",\"content\":\"hi\",\"clientMessageId\":\"" + CID + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("roomId"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        send("good", "{not json").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void wrongContentTypeIs415InTheEnvelope() throws Exception {
        mvc.perform(post("/api/messages").header("Authorization", "Bearer good").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void methodNotAllowedIsInTheEnvelope() throws Exception {
        mvc.perform(put("/api/messages/" + ID).header("Authorization", "Bearer good"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    // ---------- send: idempotency and membership (rows 1, 12, 16, 17) ----------
    @Test
    void duplicateSendReturnsTheOriginalWith200AndPublishesNothing() throws Exception {
        when(messages.insert(any(Message.class))).thenThrow(new DuplicateKeyException(
                "E11000 duplicate key error collection: messages.messages index: uk_room_sender_client_msg dup key: { }"));
        when(messages.findByRoomIdAndSenderIdAndClientMessageId(ROOM, ALICE, CID)).thenReturn(Optional.of(stored(ALICE, false)));

        send("good", sendBody("retry")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ID)).andExpect(jsonPath("$.content").value("hello"));
        verify(events, never()).created(any(Message.class));
    }

    @Test
    void duplicateOnAnotherIndexIsABugNotAReplay() throws Exception {
        when(messages.insert(any(Message.class))).thenThrow(new DuplicateKeyException(
                "E11000 duplicate key error collection: messages.messages index: _id_ dup key: { }"));
        send("good", sendBody("hi")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verify(messages, never()).findByRoomIdAndSenderIdAndClientMessageId(anyString(), anyString(), anyString());
    }

    @Test
    void membershipUnavailableIs503NotA404AndNothingIsWritten() throws Exception {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.Unavailable("no instances"));
        send("good", sendBody("hi")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        verify(messages, never()).insert(any(Message.class));
    }

    @Test
    void membershipTimeoutIs504() throws Exception {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.TimedOut());
        send("good", sendBody("hi")).andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"));
    }

    @Test
    void nonMemberIs404() throws Exception {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.NotMember());
        send("good", sendBody("hi")).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Room not found"));
    }

    // ---------- get / edit / delete (rows 10-15) ----------
    @Test
    void malformedPathIdIs400NotA500() throws Exception {
        mvc.perform(get("/api/messages/not-an-objectid").header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ID"));
        verifyNoInteractions(messages);
    }

    @Test
    void tombstoneHasEveryFieldWithNullContent() throws Exception {
        when(messages.findById(ID)).thenReturn(Optional.of(stored(ALICE, true)));
        mvc.perform(get("/api/messages/" + ID).header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.content").value(nullValue()))
                .andExpect(content().string(containsString("\"content\":null")))
                .andExpect(content().string(not(containsString("deletedBy"))));
    }

    @Test
    void editingADeletedMessageIs409() throws Exception {
        when(messages.findById(ID)).thenReturn(Optional.of(stored(ALICE, true)));
        mvc.perform(patch("/api/messages/" + ID).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MESSAGE_DELETED"));
    }

    @Test
    void editingOwnMessageSetsContentAndPublishesUpdated() throws Exception {
        when(messages.findById(ID)).thenReturn(Optional.of(stored(ALICE, false)));
        Message edited = new Message(ID, ROOM, ALICE, "new words", MessageType.TEXT, CID, T, T, null, null);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Message.class))).thenReturn(edited);
        mvc.perform(patch("/api/messages/" + ID).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  new words \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("new words"))
                .andExpect(jsonPath("$.editedAt").value("2026-10-20T10:15:30.123Z"));
        verify(events).updated(any(Message.class));
    }

    @Test
    void editingSomeoneElsesMessageIs403EvenForAnAdmin() throws Exception {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.Member(RoomRole.ADMIN));
        when(messages.findById(ID)).thenReturn(Optional.of(stored(BOB, false)));
        mvc.perform(patch("/api/messages/" + ID).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void roomAdminMayDeleteAnotherMembersMessage() throws Exception {
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.Member(RoomRole.ADMIN));
        when(messages.findById(ID)).thenReturn(Optional.of(stored(BOB, false)));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(Message.class))).thenReturn(stored(BOB, true));

        mvc.perform(delete("/api/messages/" + ID).header("Authorization", "Bearer good")).andExpect(status().isNoContent());
        verify(events).deleted(any(Message.class));
        verify(membership, times(1)).check(eq(ROOM), any());   // the role came from the one membership call
    }

    @Test
    void plainMemberMayNotDeleteAnotherMembersMessage() throws Exception {
        when(messages.findById(ID)).thenReturn(Optional.of(stored(BOB, false)));
        mvc.perform(delete("/api/messages/" + ID).header("Authorization", "Bearer good")).andExpect(status().isForbidden());
        verifyNoInteractions(mongo);
    }

    @Test
    void deletingAnAlreadyDeletedMessageIs204AndPublishesNothing() throws Exception {
        when(messages.findById(ID)).thenReturn(Optional.of(stored(ALICE, true)));
        mvc.perform(delete("/api/messages/" + ID).header("Authorization", "Bearer good")).andExpect(status().isNoContent());
        verify(events, never()).deleted(any(Message.class));
    }

    @Test
    void missingAndNotMemberAreIndistinguishable() throws Exception {
        when(messages.findById(ID)).thenReturn(Optional.empty());
        String missing = mvc.perform(get("/api/messages/" + ID).header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        when(messages.findById(ID)).thenReturn(Optional.of(stored(BOB, false)));
        when(membership.check(eq(ROOM), any())).thenReturn(new MembershipResult.NotMember());
        String hidden = mvc.perform(get("/api/messages/" + ID).header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(withoutPerRequestFields(hidden)).isEqualTo(withoutPerRequestFields(missing));
    }

    // ---------- history parameters (rows 8, 9) ----------
    @Test
    void invalidCursorIs400WithTheFixedMessage() throws Exception {
        mvc.perform(get("/api/messages").param("roomId", ROOM).param("before", "garbage!").header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.message").value("Invalid cursor"));
        verifyNoInteractions(membership);
    }

    @Test
    void limitAboveMaxIs400AtTheController() throws Exception {
        mvc.perform(get("/api/messages").param("roomId", ROOM).param("limit", "101").header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("limit"));
        verifyNoInteractions(queries);
    }

    @Test
    void missingRoomIdIs400NamingIt() throws Exception {
        mvc.perform(get("/api/messages").header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.details[0].field").value("roomId"));
    }

    // ---------- dependencies and bugs (rows 18, 20, 21) ----------
    @Test
    void unknownPathIs404InTheEnvelope() throws Exception {
        mvc.perform(get("/api/messages/x/y").header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void mongoUnavailableIs503() throws Exception {
        when(messages.findById(anyString())).thenThrow(new DataAccessResourceFailureException("Timed out after 2000 ms while waiting for a server"));
        mvc.perform(get("/api/messages/" + ID).header("Authorization", "Bearer good"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
    }

    @Test
    void anUnexpectedBugIsAGeneric500WithoutTheDetail() throws Exception {
        when(messages.findById(anyString())).thenThrow(new IllegalStateException("boom: internal detail"));
        mvc.perform(get("/api/messages/" + ID).header("Authorization", "Bearer good"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("boom"))));
    }
}
