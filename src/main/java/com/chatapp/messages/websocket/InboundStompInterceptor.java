package com.chatapp.messages.websocket;

import org.springframework.messaging.support.ChannelInterceptor;

/** Marker: every bean of this type is registered on the clientInboundChannel by WebSocketConfig, in @Order. */
public interface InboundStompInterceptor extends ChannelInterceptor {
}
