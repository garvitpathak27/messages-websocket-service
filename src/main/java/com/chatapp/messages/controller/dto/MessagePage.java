package com.chatapp.messages.controller.dto;

import java.util.List;

import com.chatapp.messages.repository.CursorCodec;
import com.chatapp.messages.repository.MessageSlice;
import com.fasterxml.jackson.annotation.JsonInclude;

/** spec §8/§9 page: items, nextCursor, hasMore — exactly what MessageSlice (Step 16) carries, cursor encoded opaque. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MessagePage(List<MessageResponse> items, String nextCursor, boolean hasMore) {

    public static MessagePage from(MessageSlice slice) {
        return new MessagePage(
                slice.items().stream().map(MessageResponse::from).toList(),
                slice.next() == null ? null : CursorCodec.encode(slice.next()),
                slice.hasMore());
    }
}
