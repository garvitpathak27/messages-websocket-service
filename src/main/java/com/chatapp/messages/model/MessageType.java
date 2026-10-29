package com.chatapp.messages.model;

/**
 * Stored as its name (a BSON string). TEXT is the only v1 type; the field exists from day one so
 * attachments or system messages (roadmap: deferred) need no migration of existing documents.
 */
public enum MessageType {
    TEXT
}
