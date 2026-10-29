package com.chatapp.messages.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** spec §9 body. There is deliberately NO senderId: the sender comes only from the token (NFR6); unknown JSON fields are ignored. */
public record SendMessageRequest(
        @NotBlank(message = "is required") @Pattern(regexp = UUID_REGEX, message = "must be a UUID") String roomId,
        @NotBlank(message = "must not be blank") String content,
        @NotBlank(message = "is required") @Pattern(regexp = UUID_REGEX, message = "must be a UUID") String clientMessageId) {

    public static final String UUID_REGEX = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";
}
