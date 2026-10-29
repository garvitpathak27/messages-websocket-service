package com.chatapp.messages.repository;

import java.time.Instant;
import java.util.Objects;

import org.bson.types.ObjectId;

import com.chatapp.messages.model.Message;

/** A point in a room's total order (createdAt, _id) — D6. Millisecond precision only, like the stored BSON date. */
public record CursorPosition(Instant createdAt, ObjectId id) {

    public CursorPosition {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
        if (createdAt.getNano() % 1_000_000 != 0) {
            // Built from an un-truncated Instant (Step 12): it would never equal any stored row.
            throw new IllegalArgumentException("createdAt must have millisecond precision: " + createdAt);
        }
    }

    public static CursorPosition of(Message m) {
        return new CursorPosition(m.getCreatedAt(), new ObjectId(m.getId()));
    }
}
