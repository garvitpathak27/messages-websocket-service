package com.chatapp.messages.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.chatapp.messages.model.Message;

/** Insert and find-by-id. Paged reads live in MessageQueryRepository. */
public interface MessageRepository extends MongoRepository<Message, String> {

    /** The idempotency lookup (D7). Served by uk_room_sender_client_msg (Step 13): same key, same index. */
    Optional<Message> findByRoomIdAndSenderIdAndClientMessageId(String roomId, String senderId, String clientMessageId);
}
