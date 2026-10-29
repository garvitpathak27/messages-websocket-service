package com.chatapp.messages.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;

import com.chatapp.messages.config.MongoIndexInitializer;

/** The exact filter/sort/limit/hint each direction sends — no database needed. */
class KeysetQueryShapeTest {

    private static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    private static final Instant T = Instant.parse("2026-10-20T10:15:30.123Z");
    private static final ObjectId ID = new ObjectId("6716c3f2a1b2c3d4e5f60718");

    @Test
    void olderIsTupleLessThanPlusARangeBoundSortedNewestFirst() {
        Query q = MessageQueryRepository.olderQuery(ROOM, new CursorPosition(T, ID), 51);

        assertThat(q.getQueryObject()).isEqualTo(new Document("roomId", ROOM)
                .append("createdAt", new Document("$lte", T))
                .append("$or", List.of(
                        new Document("createdAt", new Document("$lt", T)),
                        new Document("createdAt", T).append("_id", new Document("$lt", ID)))));
        assertThat(q.getSortObject()).isEqualTo(new Document("createdAt", -1).append("_id", -1));
        assertThat(q.getLimit()).isEqualTo(51);
        assertThat(q.getHint()).isEqualTo(MongoIndexInitializer.HISTORY_INDEX);
    }

    @Test
    void firstHistoryPageIsTheWholeRoomNewestFirst() {
        Query q = MessageQueryRepository.olderQuery(ROOM, null, 51);

        assertThat(q.getQueryObject()).isEqualTo(new Document("roomId", ROOM));
        assertThat(q.getSortObject()).isEqualTo(new Document("createdAt", -1).append("_id", -1));
    }

    @Test
    void newerIsTheExactInverseSortedOldestFirst() {
        Query q = MessageQueryRepository.newerQuery(ROOM, new CursorPosition(T, ID), 51);

        assertThat(q.getQueryObject()).isEqualTo(new Document("roomId", ROOM)
                .append("createdAt", new Document("$gte", T))
                .append("$or", List.of(
                        new Document("createdAt", new Document("$gt", T)),
                        new Document("createdAt", T).append("_id", new Document("$gt", ID)))));
        assertThat(q.getSortObject()).isEqualTo(new Document("createdAt", 1).append("_id", 1));
        assertThat(q.getHint()).isEqualTo(MongoIndexInitializer.HISTORY_INDEX);
    }
}
