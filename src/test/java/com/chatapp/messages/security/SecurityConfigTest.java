package com.chatapp.messages.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.chatapp.messages.client.AuthClient;
import com.chatapp.messages.client.AuthResult;

import io.grpc.Status;

/**
 * The real filter chain (Step 22) with auth-service replaced by a mock: every refusal path's status, code and
 * headers, and that public paths never ask auth-service. Step 23 repeats the same table against the live stack.
 */
@SpringBootTest(properties = {
        "MONGO_APP_USERNAME=test",
        "MONGO_APP_PASSWORD=test",
        "CHAT_ROOM_DETAIL_PATH=/api/rooms/{roomId}/",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AuthClient authClient;

    @Test
    void noHeaderIsTokenMissingInTheGatewayEnvelope() throws Exception {
        mvc.perform(get("/api/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("TOKEN_MISSING"))
                .andExpect(jsonPath("$.path").value("/api/messages"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.details").doesNotExist());
        verify(authClient, never()).verify(anyString());
    }

    @Test
    void malformedHeadersAreRejectedWithoutAskingAuthService() throws Exception {
        for (String h : List.of("Basic YWxpY2U6eA==", "Bearer ", "Bearer two words", "Bearer " + "a".repeat(5000))) {
            mvc.perform(get("/api/messages").header("Authorization", h))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                    .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                    .andExpect(jsonPath("$.message").value("Authorization header must be 'Bearer <token>'"));
        }
        verify(authClient, never()).verify(anyString());
    }

    @Test
    void invalidTokenIs401TokenInvalid() throws Exception {
        when(authClient.verify("bad")).thenReturn(new AuthResult.Invalid());
        mvc.perform(get("/api/messages").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"))
                .andExpect(jsonPath("$.message").value("Token is invalid or expired"));
    }

    @Test
    void authOutageIs503NotA401() throws Exception {
        when(authClient.verify("tok")).thenReturn(new AuthResult.Unavailable(Status.Code.UNAVAILABLE));
        mvc.perform(get("/api/messages").header("Authorization", "Bearer tok"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
    }

    @Test
    void validTokenGetsPastSecurityAndAuthServiceIsAskedOnce() throws Exception {
        when(authClient.verify("tok")).thenReturn(new AuthResult.Valid("usr_1", List.of("user")));
        // The controller's own 400 (roomId is required), not 401/503, proves the request got past authentication.
        mvc.perform(get("/api/messages").header("Authorization", "Bearer tok"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(authClient, times(1)).verify("tok");   // one filter in the chain, not a second servlet registration
    }

    @Test
    void postIsNotBlockedByCsrf() throws Exception {
        when(authClient.verify("tok")).thenReturn(new AuthResult.Valid("usr_1", List.of("user")));
        // Malformed JSON reaches the controller's 400. A 403 would mean CSRF is still on.
        mvc.perform(post("/api/messages").header("Authorization", "Bearer tok")
                        .contentType("application/json").content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void livenessIsPublicAndNeverAsksAuthService() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verify(authClient, never()).verify(anyString());
    }

    @Test
    void nonPublicActuatorPathNeedsAToken() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }
}
