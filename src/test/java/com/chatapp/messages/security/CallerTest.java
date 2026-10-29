package com.chatapp.messages.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

/** The token survives authentication (Step 25) and never prints. */
class CallerTest {

    private static final AuthenticatedUser USER = new AuthenticatedUser("usr_1", List.of("user"));

    @Test
    void callerCarriesTheForwardableToken() {
        var auth = UsernamePasswordAuthenticationToken.authenticated(USER, new BearerToken("secret.tok"), List.of());
        Caller caller = Caller.from(auth);
        assertThat(caller.user()).isEqualTo(USER);
        assertThat(caller.token().authorizationHeader()).isEqualTo("Bearer secret.tok");
    }

    @Test
    void noTokenMeansNoCaller() {
        var auth = UsernamePasswordAuthenticationToken.authenticated(USER, null, List.of());
        assertThatThrownBy(() -> Caller.from(auth)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Caller.from(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokenNeverPrints() {
        var token = new BearerToken("secret.tok");
        var auth = UsernamePasswordAuthenticationToken.authenticated(USER, token, List.of());
        assertThat(token.toString()).isEqualTo("BearerToken[REDACTED]");
        assertThat(auth.toString()).doesNotContain("secret.tok");
        assertThat(Caller.from(auth).toString()).doesNotContain("secret.tok");
    }
}
