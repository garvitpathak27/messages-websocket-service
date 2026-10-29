package com.chatapp.messages.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record EditMessageRequest(@NotBlank(message = "must not be blank") String content) {
}
