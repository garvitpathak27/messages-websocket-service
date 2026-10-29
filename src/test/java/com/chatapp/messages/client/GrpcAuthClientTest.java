package com.chatapp.messages.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chatapp.messages.config.GrpcChannelConfig;

import auth.Auth.TokenRequest;
import auth.Auth.TokenValidationResponse;
import auth.AuthServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

/** The whole status table against a real gRPC server in-process. No Docker, no auth-service. */
class GrpcAuthClientTest {

    private Server server;
    private ManagedChannel channel;
    private final AtomicInteger calls = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    private GrpcAuthClient client(BiConsumer<Integer, StreamObserver<TokenValidationResponse>> behaviour, long deadlineMs) throws IOException {
        String name = InProcessServerBuilder.generateName();
        InProcessServerBuilder builder = InProcessServerBuilder.forName(name);
        if (behaviour != null) {
            builder.addService(new AuthServiceGrpc.AuthServiceImplBase() {
                @Override
                public void validateToken(TokenRequest request, StreamObserver<TokenValidationResponse> out) {
                    behaviour.accept(calls.incrementAndGet(), out);
                }
            });
        }
        server = builder.build().start();
        channel = InProcessChannelBuilder.forName(name)
                .intercept(new GrpcChannelConfig.DefaultDeadlineInterceptor(deadlineMs))   // the production interceptor
                .build();
        return new GrpcAuthClient(channel, 10);
    }

    private static void reply(StreamObserver<TokenValidationResponse> out, TokenValidationResponse r) {
        out.onNext(r);
        out.onCompleted();
    }

    private static TokenValidationResponse valid() {
        return TokenValidationResponse.newBuilder().setActive(true).setUserId("usr_9a8b7c6d5e").addRoles("user").build();
    }

    @Test
    void validTokenYieldsIdentityFromTheResponse() throws IOException {
        assertThat(client((n, out) -> reply(out, valid()), 2000).verify("t"))
                .isEqualTo(new AuthResult.Valid("usr_9a8b7c6d5e", List.of("user")));
    }

    @Test
    void activeFalseIsInvalid() throws IOException {
        assertThat(client((n, out) -> reply(out, TokenValidationResponse.newBuilder().setActive(false).build()), 2000).verify("t"))
                .isEqualTo(new AuthResult.Invalid());
    }

    @Test
    void unauthenticatedStatusIsInvalidAndNotRetried() throws IOException {
        assertThat(client((n, out) -> out.onError(Status.UNAUTHENTICATED.asRuntimeException()), 2000).verify("t"))
                .isEqualTo(new AuthResult.Invalid());
        assertThat(calls).hasValue(1);
    }

    @Test
    void missingMethodIsUnimplementedWhichIsUnavailable() throws IOException {
        assertThat(client(null, 2000).verify("t")).isEqualTo(new AuthResult.Unavailable(Status.Code.UNIMPLEMENTED));
    }

    @Test
    void slowServerHitsThePerCallDeadlineAndIsNotRetried() throws IOException {
        GrpcAuthClient client = client((n, out) -> {
            try { Thread.sleep(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            reply(out, valid());
        }, 200);

        long start = System.nanoTime();
        AuthResult result = client.verify("t");
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(result).isEqualTo(new AuthResult.Unavailable(Status.Code.DEADLINE_EXCEEDED));
        assertThat(tookMs).isLessThan(1_000);   // bounded by the 200 ms deadline, not the 2 s handler
        assertThat(calls).hasValue(1);
    }

    @Test
    void unavailableIsRetriedOnceAndCanRecover() throws IOException {
        GrpcAuthClient client = client((n, out) -> {
            if (n == 1) out.onError(Status.UNAVAILABLE.asRuntimeException());
            else reply(out, valid());
        }, 2000);

        assertThat(client.verify("t")).isInstanceOf(AuthResult.Valid.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void unavailableTwiceIsUnavailable() throws IOException {
        assertThat(client((n, out) -> out.onError(Status.UNAVAILABLE.asRuntimeException()), 2000).verify("t"))
                .isEqualTo(new AuthResult.Unavailable(Status.Code.UNAVAILABLE));
        assertThat(calls).hasValue(2);
    }

    @Test
    void anyOtherStatusFailsClosed() throws IOException {
        assertThat(client((n, out) -> out.onError(Status.INTERNAL.asRuntimeException()), 2000).verify("t"))
                .isEqualTo(new AuthResult.Unavailable(Status.Code.INTERNAL));
    }

    @Test
    void validWithoutUserIdFailsClosed() throws IOException {
        assertThat(client((n, out) -> reply(out, TokenValidationResponse.newBuilder().setActive(true).build()), 2000).verify("t"))
                .isEqualTo(new AuthResult.Unavailable(Status.Code.INTERNAL));
    }
}
