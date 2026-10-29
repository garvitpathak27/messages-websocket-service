package com.chatapp.messages.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.chatapp.messages.config.ChatServiceProperties;
import com.chatapp.messages.security.BearerToken;

/** Every row of the outcome table, including retries, without chat-service or Eureka. */
class ChatServiceMembershipClientTest {

    private static final String ROOM = "93359bfa-3f1d-4544-bb6a-5a303689e159";
    private static final String URL = "http://chat-service/api/rooms/" + ROOM + "/";
    private static final ChatServiceProperties PROPS =
            new ChatServiceProperties("chat-service", "/api/rooms/{roomId}/", 1000, 2000, 1);

    private MockRestServiceServer server;
    private ChatServiceMembershipClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://chat-service");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ChatServiceMembershipClient(builder.build(), PROPS);
    }

    private static String room(String myRole) {
        return "{\"id\":\"" + ROOM + "\",\"name\":\"general\"" + (myRole == null ? "" : ",\"my_role\":\"" + myRole + "\"") + "}";
    }

    @Test
    void adminForwardsTheCallersTokenAndReturnsTheRole() {
        server.expect(once(), requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tok.en"))
                .andRespond(withSuccess(room("admin"), MediaType.APPLICATION_JSON));

        assertThat(client.check(ROOM, new BearerToken("tok.en"))).isEqualTo(new MembershipResult.Member(RoomRole.ADMIN));
        server.verify();
    }

    @Test
    void memberRole() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(room("member"), MediaType.APPLICATION_JSON));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Member(RoomRole.MEMBER));
    }

    @Test
    void notFoundIsNotMemberAndNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.NotMember());
        server.verify();
    }

    @Test
    void readTimeoutIsTimedOutAndNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.TimedOut());
        server.verify();
    }

    @Test
    void connectFailureIsRetriedOnceThenUnavailable() {
        server.expect(times(2), requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Unavailable("ConnectException"));
        server.verify();
    }

    @Test
    void connectFailureThenSuccessRecoversOnTheRetry() {
        server.expect(once(), requestTo(URL)).andRespond(withException(new SocketTimeoutException("Connect timed out")));
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(room("admin"), MediaType.APPLICATION_JSON));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Member(RoomRole.ADMIN));
        server.verify();
    }

    @Test
    void noInstancesIsRetriedOnceThenUnavailableNeverNotMember() {
        server.expect(times(2), requestTo(URL)).andRespond(request -> {
            throw new IllegalStateException("No instances available for chat-service");
        });
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Unavailable("no instances"));
        server.verify();
    }

    @Test
    void serverErrorIsUnavailableAndNotRetried() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Unavailable("chat-service 500"));
        server.verify();
    }

    @Test
    void okWithoutAKnownRoleFailsClosed() {
        server.expect(once(), requestTo(URL)).andRespond(withSuccess(room("owner"), MediaType.APPLICATION_JSON));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Unavailable("unrecognised my_role"));
    }

    @Test
    void unexpectedClientErrorIsAContractBreakNotNotMember() {
        server.expect(once(), requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Unavailable("chat-service 401"));
    }

    @Test
    void missingTrailingSlashRedirectIsUnavailableNotFollowedAndNotRetried() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY).location(URI.create(URL + "x/")));
        assertThat(client.check(ROOM, new BearerToken("t"))).isEqualTo(new MembershipResult.Unavailable("chat-service 301"));
        server.verify();
    }
}
