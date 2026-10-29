package com.chatapp.messages.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.chatapp.messages.model.Message;

/** Insert and find-by-id. Paged reads live in MessageQueryRepository; Step 28 adds the idempotency lookup. */
public interface MessageRepository extends MongoRepository<Message, String> {
}
