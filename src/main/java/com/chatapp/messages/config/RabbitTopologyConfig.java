package com.chatapp.messages.config;

import java.util.Map;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Base64UrlNamingStrategy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitRetryTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.policy.SimpleRetryPolicy;

@Configuration
@EnableConfigurationProperties(EventsProperties.class)
public class RabbitTopologyConfig {

    static final String DEAD_LETTER_ROUTING_KEY = "room-deleted";

    /** Ours. RabbitAdmin declares every bean below on first connection, never at startup. */
    @Bean
    public TopicExchange messageEventsExchange(EventsProperties props) {
        return ExchangeBuilder.topicExchange(props.exchange()).durable(true).build();
    }

    @Bean
    public Declarables roomDeletedTopology(EventsProperties props) {
        // chat-service's exchange, declared IDENTICALLY (name, type, durable; no arguments) as pinned in Step 3.
        // Any difference fails loudly with 406 PRECONDITION_FAILED instead of binding to the wrong thing.
        Exchange chatEvents = new ExchangeBuilder(props.chat().exchange(), props.chat().exchangeType()).durable(true).build();
        DirectExchange dlx = ExchangeBuilder.directExchange(props.deadLetterExchange()).durable(true).build();
        Queue queue = QueueBuilder.durable(props.roomDeletedQueue())
                .deadLetterExchange(props.deadLetterExchange())
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
        Queue dlq = QueueBuilder.durable(props.roomDeletedDlq()).build();
        Binding consume = BindingBuilder.bind(queue).to(chatEvents).with(props.chat().roomDeletedRoutingKey()).noargs();
        Binding deadLetters = BindingBuilder.bind(dlq).to(dlx).with(DEAD_LETTER_ROUTING_KEY);
        return new Declarables(chatEvents, dlx, queue, dlq, consume, deadLetters);
    }

    /**
     * One per instance: exclusive (only this connection consumes), auto-delete (gone when the instance dies),
     * non-durable, unique client-generated name (stays the same across a reconnect, so the listener resumes it).
     */
    @Bean
    public Queue broadcastQueue() {
        return new AnonymousQueue(new Base64UrlNamingStrategy("messages-ws.broadcast."));
    }

    /** Every MESSAGE_* event reaches every instance: N instances => N copies (Step 41). */
    @Bean
    public Binding broadcastBinding(@Qualifier("broadcastQueue") Queue broadcastQueue, TopicExchange messageEventsExchange,
                                    EventsProperties props) {
        return BindingBuilder.bind(broadcastQueue).to(messageEventsExchange).with(props.routingKeyPrefix() + ".#");
    }

    /** Poison messages (AmqpRejectAndDontRequeueException anywhere in the cause chain) skip the retries: DLQ after 1 attempt. */
    @Bean
    public RabbitRetryTemplateCustomizer doNotRetryPoisonMessages(
            @Value("${spring.rabbitmq.listener.simple.retry.max-attempts}") int maxAttempts) {
        return (target, template) -> {
            if (target == RabbitRetryTemplateCustomizer.Target.LISTENER) {
                template.setRetryPolicy(new SimpleRetryPolicy(maxAttempts,
                        Map.of(AmqpRejectAndDontRequeueException.class, false), true, true));
            }
        };
    }
}
