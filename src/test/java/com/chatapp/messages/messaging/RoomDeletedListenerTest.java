package com.chatapp.messages.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import com.chatapp.messages.config.EventsProperties;
import com.chatapp.messages.service.RoomCleanupService;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Poison detection: anything without a UUID at payload.<room-id-field> is rejected without requeue (-> DLQ once). */
class RoomDeletedListenerTest {

    private final RoomDeletedListener listener = new RoomDeletedListener(JsonMapper.builder().build(), mock(RoomCleanupService.class),
            new EventsProperties("messages.events", "messages.events", "messages-websocket-service",
                    new EventsProperties.Chat("chat.events", "topic", "chat.events.room_deleted", "room_id"), "q", "dlx", "dlq", 500));

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsAndLowerCasesTheRoomId() {
        assertThat(listener.roomIdFrom(b("{\"payload\":{\"room_id\":\"93359BFA-3F1D-4544-BB6A-5A303689E159\",\"type\":\"group\"}}"), "e"))
                .isEqualTo("93359bfa-3f1d-4544-bb6a-5a303689e159");
    }

    @Test
    void poisonBodiesAreRejectedWithoutRequeue() {
        for (String body : new String[] {"not json", "{}", "{\"payload\":{}}", "{\"payload\":{\"room_id\":42}}",
                "{\"payload\":{\"room_id\":\"not-a-uuid\"}}", "{\"room_id\":\"93359bfa-3f1d-4544-bb6a-5a303689e159\"}"}) {
            assertThatThrownBy(() -> listener.roomIdFrom(b(body), "e"))
                    .as(body).isInstanceOf(AmqpRejectAndDontRequeueException.class);
        }
    }
}
