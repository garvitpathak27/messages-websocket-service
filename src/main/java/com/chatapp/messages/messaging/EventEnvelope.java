package com.chatapp.messages.messaging;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * chat-service's ADR-012 envelope. Field names are the contract; assert_event.py (Step 36) is the arbiter.
 * Timestamps are Instant on purpose: it serialises with a literal Z, which assert_event.py requires (+00:00 fails).
 */
@JsonPropertyOrder({"event_id", "type", "version", "producer", "timestamp", "payload"})
public record EventEnvelope(@JsonProperty("event_id") String eventId, String type, int version, String producer,
                            Instant timestamp, Object payload) {

    public static final int VERSION = 1;

    /** spec §10: full content so live delivery needs no database round trip. */
    public record MessageCreated(@JsonProperty("message_id") String messageId, @JsonProperty("room_id") String roomId,
                                 @JsonProperty("sender_id") String senderId, String content, String type,
                                 @JsonProperty("client_message_id") String clientMessageId,
                                 @JsonProperty("created_at") Instant createdAt) {
    }

    public record MessageUpdated(@JsonProperty("message_id") String messageId, @JsonProperty("room_id") String roomId,
                                 @JsonProperty("sender_id") String senderId, String content,
                                 @JsonProperty("edited_at") Instant editedAt) {
    }

    public record MessageDeleted(@JsonProperty("message_id") String messageId, @JsonProperty("room_id") String roomId,
                                 @JsonProperty("deleted_by") String deletedBy, @JsonProperty("deleted_at") Instant deletedAt) {
    }
}
