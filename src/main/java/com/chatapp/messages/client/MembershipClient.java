package com.chatapp.messages.client;

import com.chatapp.messages.security.BearerToken;

/** Membership port (NFR12). D2 Option B (local projection) would be a second implementation of this interface. */
public interface MembershipClient {

    /** Never throws for a membership outcome. The token is passed explicitly; implementations must never log it. */
    MembershipResult check(String roomId, BearerToken callerToken);
}
