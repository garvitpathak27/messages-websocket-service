package com.chatapp.messages.messaging;

import java.io.IOException;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.chatapp.messages.websocket.RoomBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Consumes EVERY MESSAGE_* event — including the ones this instance published — through this instance's own queue.
 * Deliberate: the broker copy is the single broadcast path for all instances. Do not short-circuit it locally.
 */
@Component
public class BroadcastListener {

    private final ObjectMapper mapper;
    private final RoomBroadcaster broadcaster;

    public BroadcastListener(ObjectMapper mapper, RoomBroadcaster broadcaster) {
        this.mapper = mapper;
        this.broadcaster = broadcaster;
    }

    @RabbitListener(queues = "#{broadcastQueue.name}", concurrency = "1")   // 1: preserve this queue's order
    public void onMessageEvent(Message amqp) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(amqp.getBody());
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("MESSAGE_* event is not JSON (event_id="
                    + amqp.getMessageProperties().getMessageId() + ")");
        }
        broadcaster.broadcast(envelope.path("type").asText(), envelope.path("payload"));
    }
}
