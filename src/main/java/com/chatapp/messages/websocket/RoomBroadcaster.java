package com.chatapp.messages.websocket;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import com.chatapp.messages.controller.dto.MessageResponse;
import com.chatapp.messages.model.MessageType;
import com.chatapp.messages.repository.MessageRepository;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns a MESSAGE_* event into a frame for THIS instance's subscribers only. Never logs content (NFR9).
 * The findById below is the delivery layer's ONLY Mongo touchpoint (Step 41's extraction budget).
 */
@Component
public class RoomBroadcaster {

    public static final String ROOM_TOPIC_PREFIX = "/topic/rooms.";
    private static final Logger log = LoggerFactory.getLogger(RoomBroadcaster.class);

    /** The frame body (roadmap): type + the Phase 8 §9 message shape; deletes carry Step 31's tombstone. */
    public record Frame(String type, MessageResponse message) {
    }

    private final SimpMessagingTemplate template;
    private final SimpUserRegistry users;
    private final MessageRepository messages;

    public RoomBroadcaster(SimpMessagingTemplate template, SimpUserRegistry users, MessageRepository messages) {
        this.template = template;
        this.users = users;
        this.messages = messages;
    }

    public void broadcast(String type, JsonNode payload) {
        String roomId = payload.path("room_id").asText(null);
        String messageId = payload.path("message_id").asText(null);
        if (roomId == null || messageId == null) {
            log.warn("{} event without room_id/message_id: not broadcast", type);
            return;
        }
        String destination = ROOM_TOPIC_PREFIX + roomId;
        if (users.findSubscriptions(s -> destination.equals(s.getDestination())).isEmpty()) {
            return;   // nobody on THIS instance is in this room: the other instances handle their own sessions
        }
        MessageResponse message = switch (type) {
            case "MESSAGE_CREATED" -> fromCreatedPayload(payload);              // full content in the event (spec §10)
            case "MESSAGE_UPDATED", "MESSAGE_DELETED" ->                         // partial payloads: read the latest state
                    messages.findById(messageId).map(MessageResponse::from).orElse(null);
            default -> null;
        };
        if (message == null) {
            log.debug("{} {} not broadcast (unknown type or message gone)", type, messageId);
            return;
        }
        template.convertAndSend(destination, new Frame(type, message));
    }

    static MessageResponse fromCreatedPayload(JsonNode p) {
        return new MessageResponse(
                p.path("message_id").asText(),
                p.path("room_id").asText(),
                p.path("sender_id").asText(),
                p.path("content").asText(),
                MessageType.valueOf(p.path("type").asText(MessageType.TEXT.name())),
                Instant.parse(p.path("created_at").asText()),
                null,
                false,
                p.path("client_message_id").isNull() ? null : p.path("client_message_id").asText(null));
    }
}
