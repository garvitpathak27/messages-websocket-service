package com.chatapp.messages.client;

/** chat-service's my_role values (chat/serializers.py:51). Anything else is a contract break. */
public enum RoomRole {
    ADMIN, MEMBER;

    /** null for an unknown or missing value: the caller fails closed. */
    static RoomRole parse(String myRole) {
        if ("admin".equals(myRole)) return ADMIN;
        if ("member".equals(myRole)) return MEMBER;
        return null;
    }
}
