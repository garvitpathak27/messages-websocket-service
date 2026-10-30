package com.chatapp.messages.websocket;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import com.chatapp.messages.exception.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every STOMP rejection becomes ERROR (message header = code, body = the Phase 6 envelope). Spring then closes the
 * socket with 1002. Unknown failures get a generic body; detail goes to the log only.
 */
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(StompErrorHandler.class);

    private final ObjectMapper mapper;

    public StompErrorHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage, Throwable ex) {
        String requestId = UUID.randomUUID().toString();
        StompRejection rejection = findRejection(ex);
        String code;
        String text;
        if (rejection != null) {
            code = rejection.code();
            text = rejection.getMessage();
            log.info("STOMP frame rejected: {} request_id={}", code, requestId);
        } else if (clientMessage == null) {
            // Spring passes no message when the frame could not even be decoded (StompConversionException et al.)
            code = StompRejection.MALFORMED_FRAME;
            text = "Malformed STOMP frame";
            log.info("STOMP frame could not be decoded: {} request_id={}", ex.getClass().getSimpleName(), requestId);
        } else {
            code = ErrorResponse.INTERNAL_ERROR;
            text = "Internal error";
            log.error("unexpected STOMP failure request_id={}", requestId, ex);
        }
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(code);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(body(code, text, requestId), accessor.getMessageHeaders());
    }

    private byte[] body(String code, String text, String requestId) {
        try {
            return mapper.writeValueAsBytes(ErrorResponse.stomp(code, text, requestId));
        } catch (JsonProcessingException e) {
            return new byte[0];
        }
    }

    private static StompRejection findRejection(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof StompRejection r) {
                return r;
            }
        }
        return null;
    }
}
