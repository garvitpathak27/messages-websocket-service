package com.chatapp.messages.repository;

/**
 * Any malformed, tampered or non-canonical cursor. The message is fixed and never contains the input;
 * Step 27 maps this to 400 with the FR15 envelope. No stack trace is captured: hostile input is cheap to send.
 */
public class InvalidCursorException extends RuntimeException {

    public static final String MESSAGE = "Invalid cursor";

    InvalidCursorException(Throwable cause) {
        super(MESSAGE, cause, false, false);
    }
}
