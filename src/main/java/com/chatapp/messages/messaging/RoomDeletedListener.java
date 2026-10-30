package com.chatapp.messages.messaging;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.chatapp.messages.config.EventsProperties;
import com.chatapp.messages.service.RoomCleanupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** chat-service ROOM_DELETED -> FR13. Poison -> DLQ once; transient -> bounded retry (Step 34 YAML) -> DLQ. */
@Component
public class RoomDeletedListener {

    private static final Logger log = LoggerFactory.getLogger(RoomDeletedListener.class);
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ObjectMapper mapper;
    private final RoomCleanupService cleanup;
    private final String roomIdField;

    public RoomDeletedListener(ObjectMapper mapper, RoomCleanupService cleanup, EventsProperties props) {
        this.mapper = mapper;
        this.cleanup = cleanup;
        this.roomIdField = props.chat().roomIdField();
    }

    @RabbitListener(queues = "${messages.events.room-deleted-queue}")
    public void onRoomDeleted(Message message) {
        String eventId = message.getMessageProperties().getMessageId();
        String roomId = roomIdFrom(message.getBody(), eventId);
        long removed = cleanup.deleteRoom(roomId);
        log.info("ROOM_DELETED room={} event_id={}: removed {} messages", roomId, eventId, removed);
    }

    String roomIdFrom(byte[] body, String eventId) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("malformed ROOM_DELETED (event_id=" + eventId + "): body is not JSON");
        }
        JsonNode id = root == null ? null : root.path("payload").path(roomIdField);
        if (id == null || !id.isTextual() || !UUID.matcher(id.asText()).matches()) {
            throw new AmqpRejectAndDontRequeueException(
                    "malformed ROOM_DELETED (event_id=" + eventId + "): payload." + roomIdField + " missing or not a UUID");
        }
        return id.asText().toLowerCase(Locale.ROOT);
    }
}
