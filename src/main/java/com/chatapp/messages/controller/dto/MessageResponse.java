package com.chatapp.messages.controller.dto;

import java.time.Instant;

import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.MessageType;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * spec §9 message shape. Every field is always present (null included) so clients never branch on absence.
 * id is a String — the model already holds the ObjectId as its hex string (Step 12); no ObjectId ever reaches Jackson.
 * Tombstone (FR8): deleted=true, content=null; every other field as stored (Step 31 specifies the wire shape).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessageResponse(String id, String roomId, String senderId, String content, MessageType type,
                              Instant createdAt, Instant editedAt, boolean deleted, String clientMessageId) {

    public static MessageResponse from(Message m) {
        boolean deleted = m.isDeleted();
        return new MessageResponse(m.getId(), m.getRoomId(), m.getSenderId(), deleted ? null : m.getContent(),
                m.getType(), m.getCreatedAt(), m.getEditedAt(), deleted, m.getClientMessageId());
    }
}
