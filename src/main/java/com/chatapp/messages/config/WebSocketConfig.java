package com.chatapp.messages.config;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.sockjs.transport.handler.WebSocketTransportHandler;

import com.chatapp.messages.websocket.InboundStompInterceptor;
import com.chatapp.messages.websocket.StompErrorHandler;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";          // cloud-gateway routes /ws/info/** and /ws/** (its Phase 11)
    public static final String TOPIC_PREFIX = "/topic";

    private final ObjectProvider<InboundStompInterceptor> inboundInterceptors;
    private final StompErrorHandler errorHandler;
    private final ObjectMapper objectMapper;
    private final String[] allowedOrigins;
    private final long heartbeatMs;
    private final int inboundThreads;
    private TaskScheduler messageBrokerTaskScheduler;

    public WebSocketConfig(ObjectProvider<InboundStompInterceptor> inboundInterceptors, StompErrorHandler errorHandler,
                           ObjectMapper objectMapper,
                           @Value("${messages.ws.allowed-origins}") String[] allowedOrigins,
                           @Value("${messages.ws.heartbeat-ms}") long heartbeatMs,
                           @Value("${messages.ws.inbound-threads}") int inboundThreads) {
        this.inboundInterceptors = inboundInterceptors;
        this.errorHandler = errorHandler;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;
        this.heartbeatMs = heartbeatMs;
        this.inboundThreads = inboundThreads;
    }

    /** Spring's own scheduler bean; @Lazy breaks the cycle with the broker configuration (Spring reference docs pattern). */
    @Autowired
    public void setMessageBrokerTaskScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.messageBrokerTaskScheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setErrorHandler(errorHandler);
        registry.addEndpoint(ENDPOINT)
                .setAllowedOriginPatterns(allowedOrigins)   // handshake Origin check; requests without Origin (non-browser) pass
                .withSockJS()
                .setSuppressCors(true)                      // the gateway owns CORS
                .setSessionCookieNeeded(false)              // the JSESSIONID hint exists for HTTP-fallback stickiness; there are none
                // WebSocket transport ONLY. xhr_streaming / xhr_send / xhr / eventsource / htmlfile / jsonp are plain HTTP
                // that would hit the gateway's /ws/** UPGRADE route and need per-session stickiness: they now 404 here.
                .setTransportHandlers(new WebSocketTransportHandler(new DefaultHandshakeHandler()));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(TOPIC_PREFIX)
                .setHeartbeatValue(new long[] {heartbeatMs, heartbeatMs})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setPreservePublishOrder(true);   // CREATED before UPDATED for the same session, always
        // No setApplicationDestinationPrefixes: no @MessageMapping, no client SEND (D1; enforced in Step 40).
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(inboundThreads);
        registration.interceptors(inboundInterceptors.orderedStream().toArray(ChannelInterceptor[]::new));
    }

    /** Boot's ObjectMapper: frames serialise Instants exactly like REST (ISO-8601), not as epoch numbers. */
    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        messageConverters.add(json);
        return false;   // do not add Spring's defaults (which carry their own ObjectMapper)
    }
}
