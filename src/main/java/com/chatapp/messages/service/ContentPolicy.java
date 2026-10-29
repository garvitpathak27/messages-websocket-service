package com.chatapp.messages.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.chatapp.messages.exception.ApiException;

/** spec §8: trimmed; max length configurable (default 4000), counted in code points so an emoji is one character. */
@Component
public class ContentPolicy {

    private final int maxLength;

    public ContentPolicy(@Value("${messages.content.max-length}") int maxLength) {
        this.maxLength = maxLength;
    }

    public String normalize(String raw) {
        String trimmed = raw == null ? "" : raw.strip();
        if (trimmed.isEmpty()) {
            throw ApiException.validation("content", "must not be blank");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > maxLength) {
            throw ApiException.validation("content", "must be at most " + maxLength + " characters");
        }
        return trimmed;
    }
}
