package com.chatapp.messages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * chat-service membership call settings (messages.chat.*). A record, so Boot binds it through the constructor and
 * the compact constructor rejects a wrong value at startup instead of at the first request.
 */
@ConfigurationProperties("messages.chat")
public record ChatServiceProperties(String serviceId, String roomDetailPath,
                                    long connectTimeoutMs, long readTimeoutMs, long retryBackoffMs) {

    public ChatServiceProperties {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("messages.chat.service-id must be set");
        }
        if (roomDetailPath == null || !roomDetailPath.startsWith("/") || !roomDetailPath.contains("{roomId}")) {
            throw new IllegalArgumentException("messages.chat.room-detail-path must start with / and contain {roomId}, was: " + roomDetailPath);
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("messages.chat timeouts must be > 0");
        }
    }
}
