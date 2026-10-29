package com.chatapp.messages.messaging;

import com.chatapp.messages.model.Message;

/**
 * Called by MessageService AFTER a successful write (NFR7). Implementations must never throw for a broker failure:
 * the write already happened, so the client still gets its 2xx (spec §14). Step 33 provides the RabbitMQ implementation.
 */
public interface MessageEventPublisher {

    void created(Message message);

    void updated(Message message);

    void deleted(Message message);
}
