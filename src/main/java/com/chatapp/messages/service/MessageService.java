package com.chatapp.messages.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.chatapp.messages.client.MembershipClient;
import com.chatapp.messages.client.MembershipResult;
import com.chatapp.messages.client.RoomRole;
import com.chatapp.messages.config.MongoIndexInitializer;
import com.chatapp.messages.controller.dto.EditMessageRequest;
import com.chatapp.messages.controller.dto.MessagePage;
import com.chatapp.messages.controller.dto.MessageResponse;
import com.chatapp.messages.controller.dto.SendMessageRequest;
import com.chatapp.messages.exception.ApiException;
import com.chatapp.messages.messaging.MessageEventPublisher;
import com.chatapp.messages.model.Message;
import com.chatapp.messages.model.Message.F;
import com.chatapp.messages.repository.CursorPosition;
import com.chatapp.messages.repository.MessageQueryRepository;
import com.chatapp.messages.repository.MessageRepository;
import com.chatapp.messages.repository.MessageSlice;
import com.chatapp.messages.security.Caller;

@Service
public class MessageService {

    static final String ROOM_NOT_FOUND = "Room not found";
    static final String MESSAGE_NOT_FOUND = "Message not found";

    /** created=false means an idempotent replay (D7): same body as the first response, status 200 instead of 201. */
    public record SendResult(MessageResponse message, boolean created) {
    }

    private final MessageRepository messages;
    private final MessageQueryRepository queries;
    private final MongoTemplate mongo;
    private final MembershipClient membership;
    private final MessageEventPublisher events;
    private final ContentPolicy contentPolicy;
    private final Clock clock;

    public MessageService(MessageRepository messages, MessageQueryRepository queries, MongoTemplate mongo,
                          MembershipClient membership, MessageEventPublisher events, ContentPolicy contentPolicy, Clock clock) {
        this.messages = messages;
        this.queries = queries;
        this.mongo = mongo;
        this.membership = membership;
        this.events = events;
        this.contentPolicy = contentPolicy;
        this.clock = clock;
    }

    /** NFR2: validate -> authorize -> insert -> publish. Nothing else on this path. */
    public SendResult send(Caller caller, SendMessageRequest request) {
        String roomId = request.roomId().toLowerCase(Locale.ROOT);
        String clientMessageId = request.clientMessageId().toLowerCase(Locale.ROOT);
        String content = contentPolicy.normalize(request.content());                    // validate
        requireMember(roomId, caller, ROOM_NOT_FOUND);                                  // authorize (before any write)
        String senderId = caller.user().userId();                                       // NFR6: from the token only
        Message fresh = Message.newText(roomId, senderId, content, clientMessageId, clock.instant());
        Message saved;
        try {
            saved = messages.insert(fresh);                                              // insert
        } catch (DuplicateKeyException e) {
            if (e.getMessage() == null || !e.getMessage().contains(MongoIndexInitializer.IDEMPOTENCY_INDEX)) {
                throw e;                                                                 // E11000 on another index: a bug, not a replay
            }
            Message original = messages.findByRoomIdAndSenderIdAndClientMessageId(roomId, senderId, clientMessageId)
                    .orElseThrow(() -> e);
            return new SendResult(MessageResponse.from(original), false);               // replay: no second event
        }
        events.created(saved);                                                           // publish, AFTER the write (NFR7)
        return new SendResult(MessageResponse.from(saved), true);
    }

    /** FR3 history (after == null: newest first) or FR4 catch-up (after != null: oldest first). Tombstones stay in (FR8). */
    public MessagePage history(Caller caller, String roomId, CursorPosition before, CursorPosition after, int limit) {
        requireMember(roomId, caller, ROOM_NOT_FOUND);
        MessageSlice slice = after != null
                ? queries.findNewer(roomId, after, limit)
                : queries.findOlder(roomId, before, limit);
        return MessagePage.from(slice);
    }

    /** FR5. Missing and not-a-member produce the same 404 body: existence is not revealed (FR9). */
    public MessageResponse get(Caller caller, String id) {
        Message message = load(id);
        requireMember(message.getRoomId(), caller, MESSAGE_NOT_FOUND);
        return MessageResponse.from(message);
    }

    /** FR6: only the sender edits; a deleted message is 409 (roadmap assumption). Conditional update closes the race. */
    public MessageResponse edit(Caller caller, String id, EditMessageRequest request) {
        String content = contentPolicy.normalize(request.content());
        Message message = load(id);
        requireMember(message.getRoomId(), caller, MESSAGE_NOT_FOUND);
        if (!message.getSenderId().equals(caller.user().userId())) {
            throw ApiException.forbidden("Only the sender can edit a message");
        }
        if (message.isDeleted()) {
            throw ApiException.messageDeleted();
        }
        Message updated = mongo.findAndModify(
                Query.query(Criteria.where(F.ID).is(new ObjectId(id)).and(F.DELETED_AT).is(null)),
                new Update().set(F.CONTENT, content).set(F.EDITED_AT, clock.instant().truncatedTo(ChronoUnit.MILLIS)),
                FindAndModifyOptions.options().returnNew(true),
                Message.class);
        if (updated == null) {
            throw ApiException.messageDeleted();   // deleted between load and update
        }
        events.updated(updated);
        return MessageResponse.from(updated);
    }

    /** FR7: sender, or a room ADMIN per the SAME membership call. Idempotent: already deleted -> 204, no second event. */
    public void delete(Caller caller, String id) {
        Message message = load(id);
        RoomRole role = requireMember(message.getRoomId(), caller, MESSAGE_NOT_FOUND);
        boolean isSender = message.getSenderId().equals(caller.user().userId());
        if (!isSender && role != RoomRole.ADMIN) {
            throw ApiException.forbidden("Only the sender or a room admin can delete a message");
        }
        if (message.isDeleted()) {
            return;
        }
        Message deleted = mongo.findAndModify(
                Query.query(Criteria.where(F.ID).is(new ObjectId(id)).and(F.DELETED_AT).is(null)),
                new Update().set(F.DELETED_AT, clock.instant().truncatedTo(ChronoUnit.MILLIS)).set(F.DELETED_BY, caller.user().userId()),
                FindAndModifyOptions.options().returnNew(true),
                Message.class);
        if (deleted != null) {
            events.deleted(deleted);               // null: a concurrent delete won; still 204, its request published
        }
    }

    /** 400 for a malformed id, 404 for a missing one. Never calls chat-service. */
    private Message load(String id) {
        if (id == null || !ObjectId.isValid(id)) {
            throw ApiException.invalidId();
        }
        return messages.findById(id).orElseThrow(() -> ApiException.notFound(MESSAGE_NOT_FOUND));
    }

    /** One membership call per request; its role serves FR7. Unavailable/TimedOut are never NotMember (NFR4). */
    private RoomRole requireMember(String roomId, Caller caller, String notFoundMessage) {
        return switch (membership.check(roomId, caller.token())) {
            case MembershipResult.Member m -> m.role();
            case MembershipResult.NotMember n -> throw ApiException.notFound(notFoundMessage);
            case MembershipResult.Unavailable u -> throw ApiException.dependencyUnavailable();
            case MembershipResult.TimedOut t -> throw ApiException.dependencyTimeout();
        };
    }
}
