package com.chatapp.messages.websocket;

import org.springframework.messaging.MessagingException;

/**
 * A deliberate refusal of a STOMP frame. Extends MessagingException so AbstractMessageChannel rethrows it unwrapped
 * and StompErrorHandler sees our code and fixed message. Never carries client input or the token.
 */
public class StompRejection extends MessagingException {

    public static final String INVALID_DESTINATION = "INVALID_DESTINATION";
    public static final String FRAME_NOT_ALLOWED = "FRAME_NOT_ALLOWED";
    public static final String MALFORMED_FRAME = "MALFORMED_FRAME";

    private final String code;

    public StompRejection(String code, String fixedMessage) {
        super(fixedMessage);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
