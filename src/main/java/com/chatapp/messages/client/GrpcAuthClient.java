package com.chatapp.messages.client;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Generated from src/main/proto/auth.proto (Step 19). If the proto's package/options change, change only these imports.
import auth.Auth.TokenRequest;
import auth.Auth.TokenValidationResponse;
import auth.AuthServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

@Component
public class GrpcAuthClient implements AuthClient {

    private static final Logger log = LoggerFactory.getLogger(GrpcAuthClient.class);

    private final AuthServiceGrpc.AuthServiceBlockingStub stub;
    private final long retryBackoffMs;

    public GrpcAuthClient(ManagedChannel authChannel, @Value("${messages.auth.retry-backoff-ms}") long retryBackoffMs) {
        this.stub = AuthServiceGrpc.newBlockingStub(authChannel);   // deadline: added per call by the channel interceptor
        this.retryBackoffMs = retryBackoffMs;
    }

    @Override
    public AuthResult verify(String token) {
        try {
            return fromResponse(call(token));
        } catch (StatusRuntimeException first) {
            if (first.getStatus().getCode() != Status.Code.UNAVAILABLE || !backoff()) {
                return fromStatus(first);
            }
            try {
                return fromResponse(call(token));   // the one retry: UNAVAILABLE only, and ValidateToken is a read
            } catch (StatusRuntimeException second) {
                return fromStatus(second);
            }
        }
    }

    private TokenValidationResponse call(String token) {
        return stub.validateToken(TokenRequest.newBuilder().setAccessToken(token).build());
    }

    private AuthResult fromResponse(TokenValidationResponse r) {
        if (!r.getActive()) {
            return new AuthResult.Invalid();
        }
        if (r.getUserId().isBlank()) {
            log.error("ValidateToken returned active=true without user_id; failing closed (503). Check proto drift: scripts/check-proto-drift.sh");
            return new AuthResult.Unavailable(Status.Code.INTERNAL);
        }
        return new AuthResult.Valid(r.getUserId(), List.copyOf(r.getRolesList()));
    }

    /** Exhaustive over Status.Code: a code nobody mapped fails COMPILATION, not production. Never logs the token. */
    private AuthResult fromStatus(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return switch (code) {
            case UNAUTHENTICATED, INVALID_ARGUMENT -> new AuthResult.Invalid();
            case UNAVAILABLE, DEADLINE_EXCEEDED -> {
                log.warn("auth-service unavailable ({}): {} | cause: {} - failing closed (503)", code, e.getMessage(), e.getCause());
                yield new AuthResult.Unavailable(code);
            }
            case UNIMPLEMENTED -> {
                log.error("auth-service answered UNIMPLEMENTED for ValidateToken: PROTO DRIFT, not an outage. "
                        + "Run messages-websocket-service/scripts/check-proto-drift.sh. Status: {}", e.getStatus());
                yield new AuthResult.Unavailable(code);
            }
            case OK, CANCELLED, UNKNOWN, NOT_FOUND, ALREADY_EXISTS, PERMISSION_DENIED, RESOURCE_EXHAUSTED,
                 FAILED_PRECONDITION, ABORTED, OUT_OF_RANGE, INTERNAL, DATA_LOSS -> {
                log.error("auth-service failed ValidateToken with {}: {} - failing closed (503)", code, e.getMessage());
                yield new AuthResult.Unavailable(code);
            }
        };
    }

    private boolean backoff() {
        try {
            Thread.sleep(retryBackoffMs);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
