package com.chatapp.messages.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

class ErrorResponseJsonTest {

    @Test
    void serialisesToTheGatewaysFieldSet() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();

        String json = mapper.writeValueAsString(ErrorResponse.http(
                HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "Token is invalid or expired", "/api/messages", "rid-1"));

        // Field ORDER follows the record's component order, which is cloud-gateway's GatewayError order on purpose.
        // timestamp is wall-clock, so it is asserted separately from the fixed part.
        assertThat(json).contains(
                "\"status\":401,\"error\":\"Unauthorized\",\"code\":\"TOKEN_INVALID\","
                + "\"message\":\"Token is invalid or expired\",\"path\":\"/api/messages\",\"requestId\":\"rid-1\"");
        assertThat(json).startsWith("{\"timestamp\":\"").doesNotContain("\"details\"");
    }

    @Test
    void stompBodyDropsTheHttpOnlyFields() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();

        String json = mapper.writeValueAsString(ErrorResponse.stomp("FRAME_NOT_ALLOWED", "Writes are REST-only", "rid-2"));

        assertThat(json).doesNotContain("\"status\"").doesNotContain("\"path\"").doesNotContain("\"details\"");
        assertThat(json).contains("\"code\":\"FRAME_NOT_ALLOWED\"").contains("\"requestId\":\"rid-2\"");
    }
}
