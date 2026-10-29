package com.chatapp.messages.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.chatapp.messages.config.MongoIndexInitializer;
import com.chatapp.messages.model.Message;

@DataMongoTest
@Import({MessageQueryRepository.class, MongoIndexInitializer.class})
@Testcontainers
class MessageQueryRepositoryIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0.14");   // same tag as Step 9

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("messages"));
    }

    private static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    private static final String OTHER_ROOM = "0b6f3d1e-8c2a-4e5f-9a7b-1c2d3e4f5a6b";
    private static final Instant SAME_MS = Instant.parse("2026-10-20T10:15:30.123Z");
    private static final int PAGE = 50;   // the real default page size (FR3)
    private static final Comparator<Message> NEWEST_FIRST = Comparator
            .comparing(Message::getCreatedAt).thenComparing(m -> new ObjectId(m.getId())).reversed();

    @Autowired MongoTemplate mongoTemplate;
    @Autowired MessageQueryRepository repository;
    @Autowired MongoIndexInitializer indexes;

    @BeforeEach
    void productionIndexesAndAnEmptyCollection() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (indexes.health().getStatus() != Status.UP) {
            if (System.nanoTime() > deadline) {
                fail("production indexes never created: " + indexes.health().getDetails());
            }
            Thread.sleep(100);
        }
        mongoTemplate.remove(new Query(), Message.class);   // documents only; drop() would also remove the indexes
    }

    @Test
    void theTestDatabaseHasTheProductionIndexes() {
        List<Document> idx = mongoTemplate.getCollection(Message.COLLECTION).listIndexes().into(new ArrayList<>());

        assertThat(idx).extracting(d -> d.getString("name"))
                .contains(MongoIndexInitializer.HISTORY_INDEX, MongoIndexInitializer.IDEMPOTENCY_INDEX);
        Document uk = idx.stream().filter(d -> MongoIndexInitializer.IDEMPOTENCY_INDEX.equals(d.getString("name"))).findFirst().orElseThrow();
        assertThat(uk.getBoolean("unique")).isTrue();
        assertThat(uk.get("partialFilterExpression", Document.class))
                .isEqualTo(new Document("clientMessageId", new Document("$exists", true)));
    }

    @Test
    void historyOverOneMillisecondHasNoGapsAndNoDuplicates() {
        List<Message> inserted = insert(ROOM, 237, i -> SAME_MS);   // 4 full pages + 37, all in ONE millisecond
        insert(OTHER_ROOM, 40, i -> SAME_MS);                       // must never leak into ROOM

        List<List<Message>> pages = pageOlder(ROOM, null);

        assertThat(pages).extracting(List::size).containsExactly(50, 50, 50, 50, 37);
        assertThat(ids(pages)).doesNotHaveDuplicates().isEqualTo(ids(sorted(inserted, NEWEST_FIRST)));
    }

    @Test
    void historyAcrossAClusterBoundaryHasNoGapsAndNoDuplicates() {
        // 60 distinct earlier ms, 120 in one ms, 60 distinct later ms: page boundaries land inside the cluster
        List<Message> inserted = insert(ROOM, 240, i -> i < 60 ? SAME_MS.minusMillis(60 - i)
                : i < 180 ? SAME_MS : SAME_MS.plusMillis(i - 179));

        assertThat(ids(pageOlder(ROOM, null))).doesNotHaveDuplicates().isEqualTo(ids(sorted(inserted, NEWEST_FIRST)));
    }

    @Test
    void catchUpFromInsideTheClusterReturnsExactlyTheLaterMessagesOldestFirst() {
        List<Message> inserted = insert(ROOM, 200, i -> i < 150 ? SAME_MS : SAME_MS.plusMillis(i));
        List<Message> oldestFirst = sorted(inserted, NEWEST_FIRST.reversed());
        CursorPosition start = CursorPosition.of(oldestFirst.get(73));   // inside the same-ms cluster

        List<List<Message>> pages = pageNewer(ROOM, start);

        assertThat(ids(pages)).doesNotHaveDuplicates().isEqualTo(ids(oldestFirst.subList(74, 200)));
    }

    @Test
    void limitPlusOne_hasMoreIsExact_andNextComesFromTheLastReturnedRow() {
        insert(ROOM, 50, i -> SAME_MS.plusMillis(i));
        MessageSlice only = repository.findOlder(ROOM, null, PAGE);
        assertThat(only.items()).hasSize(50);
        assertThat(only.hasMore()).isFalse();

        String extra = insert(ROOM, 1, i -> SAME_MS.minusMillis(1)).get(0).getId();   // the 51st, oldest
        MessageSlice first = repository.findOlder(ROOM, null, PAGE);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items()).hasSize(50).extracting(Message::getId).doesNotContain(extra);
        assertThat(first.next()).isEqualTo(CursorPosition.of(first.items().get(49)));   // last RETURNED, not the probe row

        MessageSlice second = repository.findOlder(ROOM, first.next(), PAGE);
        assertThat(second.items()).extracting(Message::getId).containsExactly(extra);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void emptyRoomIsAnEmptyPageWithNoCursor() {
        MessageSlice slice = repository.findOlder(ROOM, null, PAGE);

        assertThat(slice.items()).isEmpty();
        assertThat(slice.hasMore()).isFalse();
        assertThat(slice.next()).isNull();
    }

    @Test
    void aLimitAboveTheConfiguredMaxIsRejected() {
        assertThatThrownBy(() -> repository.findOlder(ROOM, null, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("limit must be between 1 and 100, was 101");
    }

    @Test
    void negativeControl_aCreatedAtOnlyKeysetLosesSameMillisecondMessages() {
        insert(ROOM, 120, i -> SAME_MS);
        Sort newest = Sort.by(Sort.Order.desc(Message.F.CREATED_AT), Sort.Order.desc(Message.F.ID));
        Set<String> seen = new HashSet<>();

        List<Message> page1 = mongoTemplate.find(new Query(Criteria.where(Message.F.ROOM_ID).is(ROOM)).with(newest).limit(PAGE), Message.class);
        page1.forEach(m -> seen.add(m.getId()));
        Instant last = page1.get(page1.size() - 1).getCreatedAt();
        mongoTemplate.find(new Query(Criteria.where(Message.F.ROOM_ID).is(ROOM).and(Message.F.CREATED_AT).lt(last)).with(newest).limit(PAGE), Message.class)
                .forEach(m -> seen.add(m.getId()));

        assertThat(seen).hasSize(PAGE);   // the other 70 share the cursor's millisecond and are silently skipped
    }

    private List<Message> insert(String room, int n, IntFunction<Instant> createdAt) {
        List<Message> batch = IntStream.range(0, n)
                .mapToObj(i -> Message.newText(room, "usr_it_" + (i % 3), "m" + i, UUID.randomUUID().toString(), createdAt.apply(i)))
                .toList();
        return new ArrayList<>(mongoTemplate.insertAll(batch));
    }

    private List<List<Message>> pageOlder(String room, CursorPosition start) {
        List<List<Message>> pages = new ArrayList<>();
        CursorPosition cursor = start;
        for (int guard = 0; guard < 1_000; guard++) {
            MessageSlice slice = repository.findOlder(room, cursor, PAGE);
            pages.add(slice.items());
            if (!slice.hasMore()) {
                return pages;
            }
            assertThat(slice.items()).hasSize(PAGE);
            cursor = slice.next();
        }
        throw new AssertionError("pagination did not terminate");
    }

    private List<List<Message>> pageNewer(String room, CursorPosition start) {
        List<List<Message>> pages = new ArrayList<>();
        CursorPosition cursor = start;
        for (int guard = 0; guard < 1_000; guard++) {
            MessageSlice slice = repository.findNewer(room, cursor, PAGE);
            pages.add(slice.items());
            if (!slice.hasMore()) {
                return pages;
            }
            cursor = slice.next();
        }
        throw new AssertionError("pagination did not terminate");
    }

    private static List<Message> sorted(List<Message> messages, Comparator<Message> order) {
        return messages.stream().sorted(order).toList();
    }

    private static List<String> ids(List<?> pagesOrMessages) {
        return pagesOrMessages.stream()
                .flatMap(o -> o instanceof List<?> l ? l.stream() : java.util.stream.Stream.of(o))
                .map(m -> ((Message) m).getId())
                .toList();
    }
}
