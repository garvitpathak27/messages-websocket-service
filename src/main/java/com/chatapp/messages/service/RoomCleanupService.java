package com.chatapp.messages.service;

import java.util.List;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.chatapp.messages.config.EventsProperties;
import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.Message.F;

/** FR13: hard-delete a deleted room's messages in batches (roadmap: 500). Idempotent: a replay removes 0. */
@Service
public class RoomCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RoomCleanupService.class);

    private final MongoTemplate mongo;
    private final int batchSize;

    public RoomCleanupService(MongoTemplate mongo, EventsProperties props) {
        this.mongo = mongo;
        this.batchSize = props.cleanupBatchSize();
    }

    /**
     * Terminates: every iteration either removes >= 1 document from a finite set or returns.
     * Exit 1 (normal): the read finds nothing. Exit 2: the ids found were already removed by someone else.
     */
    public long deleteRoom(String roomId) {
        long total = 0;
        int batches = 0;
        while (true) {
            Query page = Query.query(Criteria.where(F.ROOM_ID).is(roomId)).limit(batchSize);
            page.fields().include(F.ID);
            List<Object> ids = mongo.find(page, Document.class, Message.COLLECTION).stream().map(d -> d.get(F.ID)).toList();
            if (ids.isEmpty()) {
                break;
            }
            long removed = mongo.remove(Query.query(Criteria.where(F.ID).in(ids)), Message.COLLECTION).getDeletedCount();
            if (removed == 0) {
                break;
            }
            total += removed;
            batches++;
        }
        log.debug("room {} cleanup: {} messages in {} batches", roomId, total, batches);
        return total;
    }
}
