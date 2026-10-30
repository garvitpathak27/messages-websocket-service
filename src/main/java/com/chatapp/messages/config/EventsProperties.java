package com.chatapp.messages.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** messages.events.* — validated at startup so a missing pinned value fails the boot, not the first event. */
@ConfigurationProperties("messages.events")
public record EventsProperties(String exchange, String routingKeyPrefix, String producer, Chat chat,
                               String roomDeletedQueue, String deadLetterExchange, String roomDeletedDlq, int cleanupBatchSize) {

    public record Chat(String exchange, String exchangeType, String roomDeletedRoutingKey, String roomIdField) {
        public Chat {
            require(exchange, "chat.exchange");
            require(exchangeType, "chat.exchange-type");
            require(roomDeletedRoutingKey, "chat.room-deleted-routing-key");
            require(roomIdField, "chat.room-id-field");
        }
    }

    public EventsProperties {
        require(exchange, "exchange");
        require(routingKeyPrefix, "routing-key-prefix");
        require(producer, "producer");
        if (chat == null) {
            throw new IllegalArgumentException("messages.events.chat must be set");
        }
        if (cleanupBatchSize < 1 || cleanupBatchSize > 10_000) {
            throw new IllegalArgumentException("messages.events.cleanup-batch-size must be 1..10000, was " + cleanupBatchSize);
        }
    }

    private static void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("messages.events." + key + " must be set");
        }
    }
}
