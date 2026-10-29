package com.chatapp.messages.repository;

import java.util.List;

import com.chatapp.messages.model.Message;

/**
 * One page. items: at most `limit`, in the direction's order. hasMore: a (limit+1)th row existed.
 * next: position of the last RETURNED item, or the request cursor if the page is empty (may be null).
 */
public record MessageSlice(List<Message> items, boolean hasMore, CursorPosition next) {
}
