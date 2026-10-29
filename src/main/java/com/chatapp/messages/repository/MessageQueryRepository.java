package com.chatapp.messages.repository;

import static com.chatapp.messages.config.MongoIndexInitializer.HISTORY_INDEX;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.Message.F;

@Repository
public class MessageQueryRepository {

    private final MongoTemplate mongoTemplate;
    private final int maxLimit;

    public MessageQueryRepository(MongoTemplate mongoTemplate, @Value("${messages.page.max-limit}") int maxLimit) {
        this.mongoTemplate = mongoTemplate;
        this.maxLimit = maxLimit;
    }

    /** FR3 history: newest first. cursor == null starts at the newest message in the room. */
    public MessageSlice findOlder(String roomId, CursorPosition cursor, int limit) {
        checkLimit(limit);
        return page(olderQuery(roomId, cursor, limit + 1), limit, cursor);
    }

    /** FR4 catch-up: oldest first, strictly after cursor. */
    public MessageSlice findNewer(String roomId, CursorPosition cursor, int limit) {
        Objects.requireNonNull(cursor, "catch-up requires a cursor");
        checkLimit(limit);
        return page(newerQuery(roomId, cursor, limit + 1), limit, cursor);
    }

    static Query olderQuery(String roomId, CursorPosition c, int fetch) {
        Criteria criteria = Criteria.where(F.ROOM_ID).is(roomId);
        if (c != null) {
            criteria = criteria
                    .and(F.CREATED_AT).lte(c.createdAt())                              // bound: scan starts at c (performance only)
                    .orOperator(
                            Criteria.where(F.CREATED_AT).lt(c.createdAt()),
                            Criteria.where(F.CREATED_AT).is(c.createdAt()).and(F.ID).lt(c.id()));   // tie-break: correctness
        }
        return new Query(criteria)
                .with(Sort.by(Sort.Order.desc(F.CREATED_AT), Sort.Order.desc(F.ID)))
                .limit(fetch)
                .withHint(HISTORY_INDEX);
    }

    static Query newerQuery(String roomId, CursorPosition c, int fetch) {
        Criteria criteria = Criteria.where(F.ROOM_ID).is(roomId)
                .and(F.CREATED_AT).gte(c.createdAt())
                .orOperator(
                        Criteria.where(F.CREATED_AT).gt(c.createdAt()),
                        Criteria.where(F.CREATED_AT).is(c.createdAt()).and(F.ID).gt(c.id()));
        return new Query(criteria)
                .with(Sort.by(Sort.Order.asc(F.CREATED_AT), Sort.Order.asc(F.ID)))
                .limit(fetch)
                .withHint(HISTORY_INDEX);   // same index, walked backward
    }

    private MessageSlice page(Query query, int limit, CursorPosition requestCursor) {
        List<Message> rows = mongoTemplate.find(query, Message.class);
        boolean hasMore = rows.size() > limit;
        // The (limit+1)th row exists ONLY to answer hasMore. It is not returned, and the next cursor is not built
        // from it: a cursor at the extra row would make the next page start after it and skip it forever.
        List<Message> items = List.copyOf(hasMore ? rows.subList(0, limit) : rows);
        CursorPosition next = items.isEmpty() ? requestCursor : CursorPosition.of(items.get(items.size() - 1));
        return new MessageSlice(items, hasMore, next);
    }

    /** The controller validates 1..max with a 400 (Step 27). Reaching this with anything else is a bug: fail it. */
    private void checkLimit(int limit) {
        if (limit < 1 || limit > maxLimit) {
            throw new IllegalArgumentException("limit must be between 1 and " + maxLimit + ", was " + limit);
        }
    }
}
