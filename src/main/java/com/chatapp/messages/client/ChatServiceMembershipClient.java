package com.chatapp.messages.client;

import java.io.IOException;
import java.net.SocketTimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;

import com.chatapp.messages.config.ChatServiceProperties;
import com.chatapp.messages.security.BearerToken;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@Component
public class ChatServiceMembershipClient implements MembershipClient {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceMembershipClient.class);
    private static final String NO_INSTANCES = "No instances available for ";
    private static final String READ_TIMED_OUT = "Read timed out";   // HttpURLConnection's read-timeout message (Step 24)

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoomView(String id, @JsonProperty("my_role") String myRole) {
    }

    /** One attempt's outcome, plus whether the failure happened before chat-service could have seen the request. */
    private record Attempt(MembershipResult result, boolean retryable) {
    }

    private final RestClient restClient;
    private final ChatServiceProperties props;

    public ChatServiceMembershipClient(@Qualifier("chatServiceRestClient") RestClient restClient, ChatServiceProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    @Override
    public MembershipResult check(String roomId, BearerToken callerToken) {
        Attempt first = attempt(roomId, callerToken);
        if (!first.retryable() || !backoff()) {
            return first.result();
        }
        return attempt(roomId, callerToken).result();   // one retry: connect-level failures only (GET is idempotent)
    }

    private Attempt attempt(String roomId, BearerToken callerToken) {
        try {
            return restClient.get()
                    .uri(props.roomDetailPath(), roomId)
                    .header(HttpHeaders.AUTHORIZATION, callerToken.authorizationHeader())   // end user's token: chat-service verifies it
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> fromResponse(roomId, response));
        } catch (ResourceAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            if (cause instanceof SocketTimeoutException && READ_TIMED_OUT.equals(cause.getMessage())) {
                log.warn("chat-service read timeout ({} ms) for room {}: {}", props.readTimeoutMs(), roomId, e.getMessage());
                return new Attempt(new MembershipResult.TimedOut(), false);   // it may be working on it: do not pile on
            }
            log.warn("chat-service unreachable for room {}: {}", roomId, e.getMessage());
            return new Attempt(new MembershipResult.Unavailable(cause.getClass().getSimpleName()), true);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(NO_INSTANCES)) {
                log.warn("chat-service has no instances in the local Eureka registry: {}", e.getMessage());
                return new Attempt(new MembershipResult.Unavailable("no instances"), true);
            }
            throw e;   // not a load-balancer outcome: a bug, let it surface
        }
    }

    private Attempt fromResponse(String roomId, ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (status.value() == 404) {
            return new Attempt(new MembershipResult.NotMember(), false);
        }
        if (status.is2xxSuccessful()) {
            RoomView room = response.bodyTo(RoomView.class);
            RoomRole role = room == null ? null : RoomRole.parse(room.myRole());
            if (role == null) {
                log.error("chat-service 200 for room {} without a recognised my_role ({}): contract break, failing closed (503)",
                        roomId, room == null ? "no body" : room.myRole());
                return new Attempt(new MembershipResult.Unavailable("unrecognised my_role"), false);
            }
            return new Attempt(new MembershipResult.Member(role), false);
        }
        if (status.value() == 301 || status.value() == 308) {
            // Django APPEND_SLASH: the configured path is missing its trailing slash. A config bug, not an outage.
            log.error("chat-service redirected room detail to {}: CHAT_ROOM_DETAIL_PATH '{}' is wrong (trailing slash?), failing closed (503)",
                    response.getHeaders().getFirst(HttpHeaders.LOCATION), props.roomDetailPath());
            return new Attempt(new MembershipResult.Unavailable("chat-service " + status.value()), false);
        }
        if (status.is5xxServerError()) {
            log.warn("chat-service answered {} for room {}: failing closed (503)", status.value(), roomId);
            return new Attempt(new MembershipResult.Unavailable("chat-service " + status.value()), false);
        }
        log.error("chat-service answered {} for room {}: contract break (expected 200 or 404), failing closed (503)", status.value(), roomId);
        return new Attempt(new MembershipResult.Unavailable("chat-service " + status.value()), false);
    }

    private boolean backoff() {
        try {
            Thread.sleep(props.retryBackoffMs());
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
