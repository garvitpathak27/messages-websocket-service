package com.chatapp.messages.client;

/** Four outcomes. Only Member lets a request through; Unavailable and TimedOut are never NotMember (NFR4). */
public sealed interface MembershipResult {

    record Member(RoomRole role) implements MembershipResult {
    }

    /** chat-service answered 404: the room does not exist for this caller (FR9). */
    record NotMember() implements MembershipResult {
    }

    /** No trustworthy answer: no instance, no connection, 5xx, or contract break. 503. */
    record Unavailable(String reason) implements MembershipResult {
    }

    /** Connected, but no answer within the read timeout. 504 (spec §14). */
    record TimedOut() implements MembershipResult {
    }
}
