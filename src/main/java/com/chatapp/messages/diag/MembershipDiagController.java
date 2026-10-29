package com.chatapp.messages.diag;

import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.chatapp.messages.client.MembershipClient;
import com.chatapp.messages.client.MembershipResult;
import com.chatapp.messages.security.Caller;

/** Profile "diag" only (Steps 25-26), still behind the bearer filter. Step 32 deletes it. */
@Profile("diag")
@RestController
class MembershipDiagController {

    private final MembershipClient membership;

    MembershipDiagController(MembershipClient membership) {
        this.membership = membership;
    }

    @GetMapping("/internal/diag/membership/{roomId}")
    ResponseEntity<Map<String, Object>> check(@PathVariable String roomId, Authentication authentication) {
        Caller caller = Caller.from(authentication);
        long start = System.nanoTime();
        MembershipResult result = membership.check(roomId, caller.token());
        long ms = (System.nanoTime() - start) / 1_000_000;
        return switch (result) {
            case MembershipResult.Member m -> ResponseEntity.ok(Map.of("outcome", "MEMBER", "role", m.role().name(), "ms", ms));
            case MembershipResult.NotMember n -> ResponseEntity.status(404).body(Map.of("outcome", "NOT_MEMBER", "ms", ms));
            case MembershipResult.Unavailable u -> ResponseEntity.status(503).body(Map.of("outcome", "UNAVAILABLE", "reason", u.reason(), "ms", ms));
            case MembershipResult.TimedOut t -> ResponseEntity.status(504).body(Map.of("outcome", "TIMED_OUT", "ms", ms));
        };
    }
}
