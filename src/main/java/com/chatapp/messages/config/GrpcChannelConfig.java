package com.chatapp.messages.config;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;

@Configuration
public class GrpcChannelConfig {

    /**
     * One channel to auth-service. Lazy: nothing connects at startup, so the service boots with auth-service down (NFR13).
     * Target "auth-service:50051" resolves through Compose DNS (dns:/// scheme), never Eureka (settled platform rule).
     * usePlaintext() is deliberate: the channel never leaves the internal `chatapp` network and auth-service is
     * unpublished. If auth-service ever moves off that network, switch to useTransportSecurity() with the platform CA
     * (or mTLS) — the bearer token is in this request and must not cross an untrusted network in clear text.
     */
    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel authChannel(@Value("${messages.auth.grpc-target}") String target,
                                      @Value("${messages.auth.deadline-ms}") long deadlineMs) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .intercept(new DefaultDeadlineInterceptor(deadlineMs))
                .build();
    }

    /**
     * gRPC has no channel-level request timeout; a call without a deadline waits forever (defeating NFR5).
     * This interceptor runs for EVERY call made through the channel and adds a deadline computed at call start,
     * unless the caller already set one. A new call site cannot forget it.
     */
    public static final class DefaultDeadlineInterceptor implements ClientInterceptor {

        private final long deadlineMs;

        public DefaultDeadlineInterceptor(long deadlineMs) {
            if (deadlineMs <= 0) {
                throw new IllegalArgumentException("messages.auth.deadline-ms must be > 0, was " + deadlineMs);
            }
            this.deadlineMs = deadlineMs;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                                   CallOptions callOptions, Channel next) {
            CallOptions options = callOptions.getDeadline() == null
                    ? callOptions.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    : callOptions;
            return next.newCall(method, options);
        }
    }
}
