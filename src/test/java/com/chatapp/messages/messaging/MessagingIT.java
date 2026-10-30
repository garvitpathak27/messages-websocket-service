package com.chatapp.messages.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.MessageType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Real application context on real Mongo + RabbitMQ (spec §17). The producer is held to every rule assert_event.py
 * checks; the consumer topology is the application's own (the test declares none of it).
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.loadbalancer.eager-load.clients=",
        "messages.chat.room-detail-path=/api/rooms/{roomId}/",
        "messages.events.chat.exchange=chat.events",
        "messages.events.chat.exchange-type=topic",
        "messages.events.chat.room-deleted-routing-key=chat.events.room_deleted",
        "messages.events.chat.room-id-field=room_id",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-interval=200ms"})
@Testcontainers
class MessagingIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0.14");
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");   // platform runs rabbitmq:3-management

    @DynamicPropertySource
    static void brokers(DynamicPropertyRegistry r) {
        r.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("messages"));
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        r.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        r.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    static final String OTHER = "0b6f3d1e-8c2a-4e5f-9a7b-1c2d3e4f5a6b";
    static final String QUEUE = "messages-websocket-service.room-deleted";
    static final String DLQ = "messages-websocket-service.room-deleted.dlq";

    @Autowired MessageEventPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired MongoTemplate mongo;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clean() {
        await(() -> admin.getQueueInfo(QUEUE) != null, "the application never declared " + QUEUE);
        mongo.remove(new Query(), Message.class);
        admin.purgeQueue(QUEUE, false);
        admin.purgeQueue(DLQ, false);
    }

    // ---------- producer: the envelope contract ----------
    @Test
    void messageCreatedSatisfiesEveryEnvelopeRule() throws Exception {
        String captured = bindCaptureQueue();
        Message m = new Message("6716c3f2a1b2c3d4e5f60718", ROOM, "usr_alice", "hello", MessageType.TEXT,
                "c6f1d1f2-6b0e-4d3e-9a59-0d7a2c3e1b44", Instant.parse("2026-10-20T10:15:30.123Z"), null, null, null);

        publisher.created(m);
        org.springframework.amqp.core.Message received = rabbit.receive(captured, 5_000);

        assertThat(received).as("an event arrived").isNotNull();
        JsonNode body = mapper.readTree(received.getBody());
        MessageProperties p = received.getMessageProperties();
        assertThat(iterable(body.fieldNames())).containsExactlyInAnyOrder("event_id", "type", "version", "producer", "timestamp", "payload");
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.get("producer").asText()).isEqualTo("messages-websocket-service");
        assertThat(body.get("timestamp").asText()).endsWith("Z");
        Instant.parse(body.get("timestamp").asText());                                         // parses as UTC instant
        UUID.fromString(body.get("event_id").asText());                                        // a valid UUID
        assertThat(p.getMessageId()).isEqualTo(body.get("event_id").asText());                 // AMQP property == event_id
        assertThat(p.getReceivedRoutingKey()).isEqualTo("messages.events." + body.get("type").asText().toLowerCase());
        assertThat(p.getReceivedRoutingKey()).isEqualTo("messages.events.message_created");
        assertThat(p.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(p.getContentType()).isEqualTo("application/json");
        assertThat(iterable(body.get("payload").fieldNames())).containsExactlyInAnyOrder(
                "message_id", "room_id", "sender_id", "content", "type", "client_message_id", "created_at");
        assertThat(body.at("/payload/created_at").asText()).isEqualTo("2026-10-20T10:15:30.123Z");
    }

    @Test
    void updatedAndDeletedUseTheirOwnRoutingKeysAndPayloads() throws Exception {
        String captured = bindCaptureQueue();
        Instant t = Instant.parse("2026-10-20T10:20:00.000Z");
        Message deleted = new Message("6716c3f2a1b2c3d4e5f60718", ROOM, "usr_alice", "x", MessageType.TEXT, null, t, t, t, "usr_admin");

        publisher.updated(deleted);
        publisher.deleted(deleted);

        JsonNode up = mapper.readTree(rabbit.receive(captured, 5_000).getBody());
        org.springframework.amqp.core.Message delMsg = rabbit.receive(captured, 5_000);
        JsonNode del = mapper.readTree(delMsg.getBody());
        assertThat(up.get("type").asText()).isEqualTo("MESSAGE_UPDATED");
        assertThat(iterable(up.get("payload").fieldNames())).containsExactlyInAnyOrder("message_id", "room_id", "sender_id", "content", "edited_at");
        assertThat(delMsg.getMessageProperties().getReceivedRoutingKey()).isEqualTo("messages.events.message_deleted");
        assertThat(iterable(del.get("payload").fieldNames())).containsExactlyInAnyOrder("message_id", "room_id", "deleted_by", "deleted_at");
    }

    // ---------- consumer: FR13 ----------
    @Test
    void roomDeletedRemovesEveryMessageInBatchesAndAReplayRemovesNothing() {
        insert(ROOM, 1_203);                       // 500 + 500 + 203
        insert(OTHER, 5);
        String body = roomDeleted(ROOM);

        send(body, "evt-1");
        await(() -> count(ROOM) == 0, "room never emptied");
        assertThat(count(OTHER)).isEqualTo(5);

        send(body, "evt-1");                       // at-least-once: the same event again
        sleep(1_000);
        assertThat(count(ROOM)).isZero();
        assertThat(admin.getQueueInfo(DLQ).getMessageCount()).isZero();   // the replay succeeded, it was not rejected
    }

    @Test
    void aPoisonMessageIsDeadLetteredExactlyOnce() {
        send("not json", "poison-1");

        org.springframework.amqp.core.Message dead = receiveOrFail(DLQ);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deaths = (List<Map<String, Object>>) dead.getMessageProperties().getHeaders().get("x-death");
        assertThat(deaths).hasSize(1);
        assertThat(((Number) deaths.get(0).get("count")).longValue()).isEqualTo(1L);   // once — not a requeue loop
        assertThat(admin.getQueueInfo(QUEUE).getMessageCount()).isZero();
    }

    @Test
    void aMissingRoomIdIsPoisonToo() {
        send("{\"event_id\":\"e\",\"type\":\"ROOM_DELETED\",\"version\":1,\"payload\":{}}", "poison-2");
        assertThat(receiveOrFail(DLQ).getMessageProperties().getMessageId()).isEqualTo("poison-2");
    }

    // ---------- helpers ----------
    private String bindCaptureQueue() {
        Queue q = new AnonymousQueue();
        admin.declareQueue(q);
        admin.declareBinding(BindingBuilder.bind(q).to(new TopicExchange("messages.events")).with("messages.events.#"));
        return q.getName();
    }

    private void insert(String room, int n) {
        mongo.insertAll(IntStream.range(0, n)
                .mapToObj(i -> Message.newText(room, "usr_it", "m" + i, UUID.randomUUID().toString(), Instant.now()))
                .toList());
    }

    private long count(String room) {
        return mongo.count(Query.query(Criteria.where("roomId").is(room)), Message.class);
    }

    private static String roomDeleted(String room) {
        return "{\"event_id\":\"" + UUID.randomUUID() + "\",\"type\":\"ROOM_DELETED\",\"version\":1,\"producer\":\"chat-service\","
                + "\"timestamp\":\"2026-10-20T10:00:00.000Z\",\"payload\":{\"room_id\":\"" + room + "\"}}";
    }

    private void send(String body, String messageId) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setContentType("application/json");
        rabbit.send("chat.events", "chat.events.room_deleted", new org.springframework.amqp.core.Message(body.getBytes(StandardCharsets.UTF_8), p));
    }

    private org.springframework.amqp.core.Message receiveOrFail(String queue) {
        org.springframework.amqp.core.Message m = rabbit.receive(queue, 10_000);
        if (m == null) fail("nothing arrived on " + queue);
        return m;
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail(message);
            sleep(100);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
