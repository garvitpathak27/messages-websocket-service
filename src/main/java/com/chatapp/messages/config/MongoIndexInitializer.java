package com.chatapp.messages.config;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import com.chatapp.messages.model.Message;
import com.mongodb.MongoCommandException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexModel;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import jakarta.annotation.PreDestroy;

/**
 * Creates the two v1 indexes on every boot (identical definitions are a no-op in MongoDB),
 * retrying in the background until it succeeds, and reports progress as the "messageIndexes"
 * health component. While it is DOWN the instance is DOWN in Eureka (Step 10), because without
 * the unique index the database cannot enforce idempotent sends (FR2).
 */
@Component("messageIndexesHealthIndicator")   // Actuator strips "HealthIndicator": component key = "messageIndexes"
public class MongoIndexInitializer implements HealthIndicator {

    public static final String HISTORY_INDEX = "ix_room_created_id";
    public static final String IDEMPOTENCY_INDEX = "uk_room_sender_client_msg";

    /** ESR: Equality roomId, then Sort createdAt desc with _id desc as tie-break; the cursor Range is on those same sort keys. */
    static final IndexModel HISTORY = new IndexModel(
            Indexes.compoundIndex(
                    Indexes.ascending(Message.F.ROOM_ID),
                    Indexes.descending(Message.F.CREATED_AT),
                    Indexes.descending(Message.F.ID)),
            new IndexOptions().name(HISTORY_INDEX));

    /** Partial: documents without clientMessageId are not in the index at all, so they never collide on a null key. */
    static final IndexModel IDEMPOTENCY = new IndexModel(
            Indexes.ascending(Message.F.ROOM_ID, Message.F.SENDER_ID, Message.F.CLIENT_MESSAGE_ID),
            new IndexOptions()
                    .name(IDEMPOTENCY_INDEX)
                    .unique(true)
                    .partialFilterExpression(Filters.exists(Message.F.CLIENT_MESSAGE_ID)));

    private static final Logger log = LoggerFactory.getLogger(MongoIndexInitializer.class);
    private static final int MAX_ERROR_CHARS = 400;

    private final MongoTemplate mongoTemplate;
    private final long retrySeconds;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mongo-index-init");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger attempts = new AtomicInteger();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private volatile List<String> created;   // null until createIndexes succeeded

    public MongoIndexInitializer(MongoTemplate mongoTemplate,
                                 @Value("${messages.mongo.index-retry-seconds}") long retrySeconds) {
        this.mongoTemplate = mongoTemplate;
        this.retrySeconds = retrySeconds;
    }

    /** After startup, never during it: a down Mongo must not stop the service from booting (NFR13, Step 11). */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        log.info("Ensuring Mongo indexes on {} (retry every {}s until done)", Message.COLLECTION, retrySeconds);
        executor.execute(this::attempt);
    }

    private void attempt() {
        int n = attempts.incrementAndGet();
        try {
            List<String> names = mongoTemplate.getCollection(Message.COLLECTION).createIndexes(List.of(HISTORY, IDEMPOTENCY));
            created = List.copyOf(names);
            lastError.set(null);
            log.info("Mongo indexes ensured on {}: {} (attempt {})", Message.COLLECTION, names, n);
            executor.shutdown();
        } catch (MongoCommandException e) {
            // 13 Unauthorized, 85 IndexOptionsConflict, 86 IndexKeySpecsConflict: a human must act.
            // Keep retrying so the fix (grant, drop old index) takes effect without a restart.
            record(e);
            log.error("Mongo index creation rejected (attempt {}), retrying in {}s: {}", n, retrySeconds, e.getMessage());
            retryLater();
        } catch (RuntimeException e) {
            // MongoTimeoutException, MongoSocketException, MongoSecurityException: usually Mongo is down or restarting.
            record(e);
            log.warn("Mongo index creation failed (attempt {}), retrying in {}s: {}", n, retrySeconds, e.toString());
            retryLater();
        }
    }

    private void record(RuntimeException e) {
        String text = e.getClass().getName() + ": " + e.getMessage();
        lastError.set(text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) + "..." : text);
    }

    private void retryLater() {
        if (!executor.isShutdown()) {
            executor.schedule(this::attempt, retrySeconds, TimeUnit.SECONDS);
        }
    }

    @Override
    public Health health() {
        List<String> done = created;
        if (done != null) {
            return Health.up().withDetail("indexes", done).build();
        }
        Health.Builder b = Health.down().withDetail("indexes", "pending").withDetail("attempts", attempts.get());
        String err = lastError.get();
        if (err != null) {
            b.withDetail("lastError", err);
        }
        return b.build();
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
