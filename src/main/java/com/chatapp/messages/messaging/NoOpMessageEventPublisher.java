package com.chatapp.messages.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.chatapp.messages.model.Message;

/** The Step 28 seam. NOT a finished feature: Step 33 deletes this class. Logs ids only, never content (NFR9). */
@Component
public class NoOpMessageEventPublisher implements MessageEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(NoOpMessageEventPublisher.class);

    public NoOpMessageEventPublisher() {
        log.warn("MESSAGE_* events are NOT published: NoOpMessageEventPublisher is the Step 28 seam; Step 33 replaces it");
    }

    @Override
    public void created(Message message) {
        log.debug("seam: MESSAGE_CREATED {} not published (Step 33)", message.getId());
    }

    @Override
    public void updated(Message message) {
        log.debug("seam: MESSAGE_UPDATED {} not published (Step 33)", message.getId());
    }

    @Override
    public void deleted(Message message) {
        log.debug("seam: MESSAGE_DELETED {} not published (Step 33)", message.getId());
    }
}
