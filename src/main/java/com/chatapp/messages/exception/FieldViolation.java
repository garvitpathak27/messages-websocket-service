package com.chatapp.messages.exception;

/** One entry of error.details for VALIDATION_FAILED / INVALID_PARAMETER. Never carries the rejected value. */
public record FieldViolation(String field, String message) {
}
