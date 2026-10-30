package com.chatapp.messages.websocket;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import com.chatapp.messages.client.MembershipClient;
import com.chatapp.messages.client.MembershipResult;
import com.chatapp.messages.exception.ErrorResponse;
import com.chatapp.messages.security.Caller;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Allow-list for every inbound frame after CONNECT. SUBSCRIBE: exact room destination + membership (FR9, NFR4).
 * SEND and everything else: rejected (D1) — the simple broker would otherwise deliver client SENDs to subscribers.
 *
 * DECISIONS.md SD D-1: NotMember (permanent, client-specific) is ERROR + close. Unavailable / TimedOut (transient,
 * nothing to do with this client) reject ONLY that subscription and keep the session, so a chat-service outage does
 * not drop the client's other subscriptions or put every client into a reconnect loop.
 */
@Component
@Order(2)
public class StompSubscribeAuthorizer implements InboundStompInterceptor {

    /** Frame body type on the rejected subscription; clients retry that subscription with backoff + jitter (Step 56). */
    public static final String SUBSCRIPTION_REJECTED = "SUBSCRIPTION_REJECTED";

    /** Exact, anchored, lowercase UUID. No '*', no '**', no suffix: the simple broker treats wildcards as patterns. */
    static final Pattern ROOM_TOPIC = Pattern.compile(
            "^/topic/rooms\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$");

    private static final Logger log = LoggerFactory.getLogger(StompSubscribeAuthorizer.class);

    /** The body of a SUBSCRIPTION_REJECTED frame: a type discriminator plus the platform error envelope. */
    record Rejected(String type, ErrorResponse error) {
    }

    private final MembershipClient membership;
    private final MessageChannel clientOutboundChannel;
    private final ObjectMapper mapper;

    public StompSubscribeAuthorizer(MembershipClient membership,
                                    @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
                                    ObjectMapper mapper) {
        this.membership = membership;
        this.clientOutboundChannel = clientOutboundChannel;
        this.mapper = mapper;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getMessageType() == null) {
            return message;
        }
        switch (accessor.getMessageType()) {
            case CONNECT, HEARTBEAT, UNSUBSCRIBE, DISCONNECT -> {
                return message;
            }
            case SUBSCRIBE -> {
                return authorizeSubscribe(accessor) ? message : null;   // null: this SUBSCRIBE is never registered
            }
            default -> throw new StompRejection(StompRejection.FRAME_NOT_ALLOWED,
                    "Only CONNECT, SUBSCRIBE, UNSUBSCRIBE and DISCONNECT are accepted; writes are REST-only");
        }
    }

    /** true: register the subscription. false: rejected alone, client told, session kept. Throws: ERROR + close. */
    private boolean authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        Matcher m = destination == null ? null : ROOM_TOPIC.matcher(destination);
        if (m == null || !m.matches()) {
            throw new StompRejection(StompRejection.INVALID_DESTINATION,
                    "Only /topic/rooms.{roomId} with a lowercase UUID may be subscribed");
        }
        if (!(accessor.getUser() instanceof Authentication authentication)) {
            throw new StompRejection(ErrorResponse.TOKEN_MISSING, "Not authenticated");
        }
        Caller caller = Caller.from(authentication);
        return switch (membership.check(m.group(1), caller.token())) {
            case MembershipResult.Member member -> true;
            case MembershipResult.NotMember n -> throw new StompRejection(ErrorResponse.NOT_FOUND, "Room not found");
            case MembershipResult.Unavailable u -> rejectOnly(accessor, ErrorResponse.DEPENDENCY_UNAVAILABLE,
                    "A required service is unavailable; retry later");
            case MembershipResult.TimedOut t -> rejectOnly(accessor, ErrorResponse.DEPENDENCY_TIMEOUT,
                    "A required service did not respond in time; retry later");
        };
    }

    /**
     * Tells the client on the subscription it asked for: a MESSAGE frame carrying that subscription's id, so STOMP
     * clients route it to that subscription's callback. Never silent (a silent drop looks like a quiet room).
     */
    private boolean rejectOnly(StompHeaderAccessor subscribe, String code, String text) {
        String requestId = UUID.randomUUID().toString();
        log.info("STOMP subscription rejected (session kept): {} request_id={}", code, requestId);
        StompHeaderAccessor out = StompHeaderAccessor.create(StompCommand.MESSAGE);
        out.setSessionId(subscribe.getSessionId());
        out.setSubscriptionId(subscribe.getSubscriptionId());
        out.setDestination(subscribe.getDestination());
        out.setMessage(code);
        out.setContentType(MimeTypeUtils.APPLICATION_JSON);
        out.setLeaveMutable(true);
        try {
            byte[] body = mapper.writeValueAsBytes(new Rejected(SUBSCRIPTION_REJECTED, ErrorResponse.stomp(code, text, requestId)));
            clientOutboundChannel.send(MessageBuilder.createMessage(body, out.getMessageHeaders()));
        } catch (JsonProcessingException e) {
            throw new StompRejection(code, text);   // cannot tell the client politely: fall back to ERROR + close
        }
        return false;
    }
}
