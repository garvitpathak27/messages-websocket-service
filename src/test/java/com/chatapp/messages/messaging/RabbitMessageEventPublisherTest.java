package com.chatapp.messages.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.chatapp.messages.config.EventsProperties;
import com.chatapp.messages.model.Message;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** Spec §14: a broker outage at publish time must never fail the write that already succeeded. */
class RabbitMessageEventPublisherTest {

    @Test
    void aBrokerOutageIsLoggedNeverThrown() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        doThrow(new AmqpConnectException(new java.net.UnknownHostException("rabbitmq")))
                .when(rabbit).send(anyString(), anyString(), any(org.springframework.amqp.core.Message.class), any(CorrelationData.class));
        EventsProperties props = new EventsProperties("messages.events", "messages.events", "messages-websocket-service",
                new EventsProperties.Chat("chat.events", "topic", "chat.events.room_deleted", "room_id"),
                "q", "dlx", "dlq", 500);
        var publisher = new RabbitMessageEventPublisher(rabbit, JsonMapper.builder().findAndAddModules().build(), props,
                Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC));
        Message m = Message.newText("93359bfa-3f1d-4544-bb6a-5a303689e159", "usr_1", "hi", null, Instant.now());

        assertThatCode(() -> publisher.created(m)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.updated(m)).doesNotThrowAnyException();
        assertThatCode(() -> publisher.deleted(m)).doesNotThrowAnyException();
    }
}
