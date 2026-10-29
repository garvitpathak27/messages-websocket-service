package com.chatapp.messages.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;
import org.springframework.data.mongodb.core.mapping.MongoId;

/**
 * One chat message (spec §8). Mutations after insert (edit, soft delete) are targeted
 * MongoTemplate updates in Step 31, never a load-modify-save of this object.
 */
@Document(collection = Message.COLLECTION)
public class Message {

    public static final String COLLECTION = "messages";

    /** Stored field names. The single source for @Field, MongoIndexInitializer (Step 13) and queries (Step 16). */
    public static final class F {
        public static final String ID = "_id";
        public static final String ROOM_ID = "roomId";
        public static final String SENDER_ID = "senderId";
        public static final String CONTENT = "content";
        public static final String TYPE = "type";
        public static final String CLIENT_MESSAGE_ID = "clientMessageId";
        public static final String CREATED_AT = "createdAt";
        public static final String EDITED_AT = "editedAt";
        public static final String DELETED_AT = "deletedAt";
        public static final String DELETED_BY = "deletedBy";

        private F() {
        }
    }

    /** Stored as ObjectId; exposed as its 24-char hex string. Null until inserted (the driver generates it). */
    @MongoId(FieldType.OBJECT_ID)
    private String id;

    /** chat-service room UUID, stored as a string (D8). */
    @Field(F.ROOM_ID)
    private String roomId;

    /** auth-service user id, taken only from ValidateToken (NFR6). */
    @Field(F.SENDER_ID)
    private String senderId;

    /** Raw text, trimmed by Step 27. Kept in storage after soft delete; every read path returns null for a tombstone. */
    @Field(F.CONTENT)
    private String content;

    @Field(F.TYPE)
    private MessageType type;

    /** Client UUID for idempotency (D7). When null it is NOT written, so the partial unique index ignores it. */
    @Field(F.CLIENT_MESSAGE_ID)
    private String clientMessageId;

    /** Server clock, UTC, millisecond precision; stored as BSON date (D6). The cursor (Step 15) encodes its epoch millis. */
    @Field(F.CREATED_AT)
    private Instant createdAt;

    @Field(F.EDITED_AT)
    private Instant editedAt;

    @Field(F.DELETED_AT)
    private Instant deletedAt;

    @Field(F.DELETED_BY)
    private String deletedBy;

    @PersistenceCreator
    public Message(String id, String roomId, String senderId, String content, MessageType type,
                   String clientMessageId, Instant createdAt, Instant editedAt, Instant deletedAt, String deletedBy) {
        this.id = id;
        this.roomId = roomId;
        this.senderId = senderId;
        this.content = content;
        this.type = type;
        this.clientMessageId = clientMessageId;
        this.createdAt = createdAt;
        this.editedAt = editedAt;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    /**
     * A new TEXT message. createdAt is truncated to milliseconds HERE, because BSON dates hold millis:
     * an un-truncated Instant would differ from what Mongo stores, and a cursor built from the
     * in-memory object (Step 15) would not match the stored row.
     */
    public static Message newText(String roomId, String senderId, String content, String clientMessageId, Instant now) {
        return new Message(null, roomId, senderId, content, MessageType.TEXT, clientMessageId,
                now.truncatedTo(ChronoUnit.MILLIS), null, null, null);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getId() { return id; }
    public String getRoomId() { return roomId; }
    public String getSenderId() { return senderId; }
    public String getContent() { return content; }
    public MessageType getType() { return type; }
    public String getClientMessageId() { return clientMessageId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEditedAt() { return editedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public String getDeletedBy() { return deletedBy; }
}
