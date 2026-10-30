package com.chatapp.messages.messaging;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.chatapp.messages.config.EventsProperties;
import com.chatapp.messages.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Publishes after the write (NFR7) and NEVER throws: the write already succeeded, so the client gets its 2xx (spec §14).
 * A lost event is logged at ERROR with event_id and message_id; subscribers recover with FR4 catch-up. Never logs content.
 */
@Component
public class RabbitMessageEventPublisher implements MessageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessageEventPublisher.class);

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final EventsProperties props;
    private final Clock clock;

    public RabbitMessageEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper, EventsProperties props, Clock clock) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.rabbit.setConfirmCallback((correlation, ack, cause) -> {   // async: off the request thread
            if (!ack) {
                log.error("broker NACKed event_id={}: {} - event lost; subscribers must catch up (FR4)",
                        correlation == null ? "?" : correlation.getId(), cause);
            }
        });
    }

    @Override
    public void created(Message m) {
        publish("MESSAGE_CREATED", m.getId(), new EventEnvelope.MessageCreated(m.getId(), m.getRoomId(), m.getSenderId(),
                m.getContent(), m.getType().name(), m.getClientMessageId(), m.getCreatedAt()));
    }

    @Override
    public void updated(Message m) {
        publish("MESSAGE_UPDATED", m.getId(), new EventEnvelope.MessageUpdated(m.getId(), m.getRoomId(), m.getSenderId(),
                m.getContent(), m.getEditedAt()));
    }

    @Override
    public void deleted(Message m) {
        publish("MESSAGE_DELETED", m.getId(), new EventEnvelope.MessageDeleted(m.getId(), m.getRoomId(), m.getDeletedBy(),
                m.getDeletedAt()));
    }

    private void publish(String type, String messageId, Object payload) {
        String eventId = UUID.randomUUID().toString();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        String routingKey = props.routingKeyPrefix() + "." + type.toLowerCase(Locale.ROOT);
        try {
            byte[] body = mapper.writeValueAsBytes(new EventEnvelope(eventId, type, EventEnvelope.VERSION, props.producer(), now, payload));
            MessageProperties p = new MessageProperties();
            p.setMessageId(eventId);                          // AMQP message_id == envelope event_id (ADR-012)
            p.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            p.setContentEncoding("UTF-8");
            p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            p.setType(type);
            p.setAppId(props.producer());
            p.setTimestamp(Date.from(now));
            p.setHeader("x-event-version", EventEnvelope.VERSION);   // chat-service's publisher sets this too
            // correlation_id ties the event to the REST request that caused it, as chat-service's publisher does.
            // MDC is populated by Step 50's RequestIdFilter; until then this is null and the property is absent.
            String requestId = MDC.get("request_id");
            if (requestId != null) {
                p.setCorrelationId(requestId);
            }
            rabbit.send(props.exchange(), routingKey, new org.springframework.amqp.core.Message(body, p), new CorrelationData(eventId));
            log.debug("published {} event_id={} message_id={}", type, eventId, messageId);
        } catch (AmqpException | JsonProcessingException e) {
            log.error("event NOT published: {} event_id={} message_id={} - the write succeeded; subscribers must catch up (FR4): {}",
                    type, eventId, messageId, e.toString());
        }
    }
}
