package com.chatapp.messages.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpSubscription;
import org.springframework.messaging.simp.user.SimpUserRegistry;

import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.MessageType;
import com.chatapp.messages.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Step 41: local-only push; CREATED from the payload (no read), UPDATED/DELETED from the stored message (tombstone). */
class RoomBroadcasterTest {

    static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    static final String ID = "6716c3f2a1b2c3d4e5f60718";

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private final SimpUserRegistry users = mock(SimpUserRegistry.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final RoomBroadcaster broadcaster = new RoomBroadcaster(template, users, messages);

    private static JsonNode json(String s) throws Exception {
        return JsonMapper.builder().build().readTree(s);
    }

    private void someoneHereIsSubscribed(boolean yes) {
        when(users.findSubscriptions(any())).thenReturn(yes ? Set.of(mock(SimpSubscription.class)) : Set.of());
    }

    @Test
    void createdIsBuiltFromThePayloadWithoutADatabaseRead() throws Exception {
        someoneHereIsSubscribed(true);
        broadcaster.broadcast("MESSAGE_CREATED", json("{\"message_id\":\"" + ID + "\",\"room_id\":\"" + ROOM + "\",\"sender_id\":\"usr_a\","
                + "\"content\":\"hi\",\"type\":\"TEXT\",\"client_message_id\":null,\"created_at\":\"2026-10-20T10:15:30.123Z\"}"));

        ArgumentCaptor<RoomBroadcaster.Frame> frame = ArgumentCaptor.forClass(RoomBroadcaster.Frame.class);
        verify(template).convertAndSend(eq("/topic/rooms." + ROOM), frame.capture());
        assertThat(frame.getValue().type()).isEqualTo("MESSAGE_CREATED");
        assertThat(frame.getValue().message().content()).isEqualTo("hi");
        assertThat(frame.getValue().message().createdAt()).isEqualTo(Instant.parse("2026-10-20T10:15:30.123Z"));
        assertThat(frame.getValue().message().clientMessageId()).isNull();
        verifyNoInteractions(messages);
    }

    @Test
    void deletedCarriesTheTombstoneFromTheStore() throws Exception {
        someoneHereIsSubscribed(true);
        Instant t = Instant.parse("2026-10-20T10:20:00Z");
        when(messages.findById(ID)).thenReturn(Optional.of(new Message(ID, ROOM, "usr_a", "secret", MessageType.TEXT, null, t, null, t, "usr_a")));

        broadcaster.broadcast("MESSAGE_DELETED", json("{\"message_id\":\"" + ID + "\",\"room_id\":\"" + ROOM + "\"}"));

        ArgumentCaptor<RoomBroadcaster.Frame> frame = ArgumentCaptor.forClass(RoomBroadcaster.Frame.class);
        verify(template).convertAndSend(eq("/topic/rooms." + ROOM), frame.capture());
        assertThat(frame.getValue().message().deleted()).isTrue();
        assertThat(frame.getValue().message().content()).isNull();
    }

    @Test
    void noLocalSubscriberMeansNothingIsSentOrRead() throws Exception {
        someoneHereIsSubscribed(false);
        broadcaster.broadcast("MESSAGE_UPDATED", json("{\"message_id\":\"" + ID + "\",\"room_id\":\"" + ROOM + "\"}"));
        verify(template, never()).convertAndSend(anyString(), any(Object.class));
        verifyNoInteractions(messages);
    }
}
