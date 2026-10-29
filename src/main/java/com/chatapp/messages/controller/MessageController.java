package com.chatapp.messages.controller;

import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chatapp.messages.controller.dto.EditMessageRequest;
import com.chatapp.messages.controller.dto.MessagePage;
import com.chatapp.messages.controller.dto.MessageResponse;
import com.chatapp.messages.controller.dto.SendMessageRequest;
import com.chatapp.messages.exception.ApiException;
import com.chatapp.messages.repository.CursorCodec;
import com.chatapp.messages.repository.CursorPosition;
import com.chatapp.messages.security.Caller;
import com.chatapp.messages.service.MessageService;

import jakarta.validation.Valid;

/** spec §9. Base path /api/messages (not nested under /api/rooms, which the gateway routes to chat-service). */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private static final Pattern UUID = Pattern.compile(SendMessageRequest.UUID_REGEX);

    private final MessageService service;
    private final int defaultLimit;
    private final int maxLimit;

    public MessageController(MessageService service,
                             @Value("${messages.page.default-limit}") int defaultLimit,
                             @Value("${messages.page.max-limit}") int maxLimit) {
        this.service = service;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> send(@Valid @RequestBody SendMessageRequest body, Authentication authentication) {
        MessageService.SendResult result = service.send(Caller.from(authentication), body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.message());
    }

    @GetMapping
    public MessagePage history(@RequestParam(required = false) String roomId,
                               @RequestParam(required = false) String before,
                               @RequestParam(required = false) String after,
                               @RequestParam(required = false) String limit,
                               Authentication authentication) {
        String room = requireRoomId(roomId);
        if (before != null && after != null) {
            throw ApiException.badParameter("before", "before and after cannot both be present");
        }
        int pageSize = parseLimit(limit);
        CursorPosition beforePosition = before == null ? null : CursorCodec.decode(before);   // InvalidCursorException -> 400
        CursorPosition afterPosition = after == null ? null : CursorCodec.decode(after);
        return service.history(Caller.from(authentication), room, beforePosition, afterPosition, pageSize);
    }

    @GetMapping("/{id}")
    public MessageResponse get(@PathVariable String id, Authentication authentication) {
        return service.get(Caller.from(authentication), id);
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MessageResponse edit(@PathVariable String id, @Valid @RequestBody EditMessageRequest body, Authentication authentication) {
        return service.edit(Caller.from(authentication), id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication authentication) {
        service.delete(Caller.from(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private static String requireRoomId(String roomId) {
        if (roomId == null || roomId.isBlank()) {
            throw ApiException.badParameter("roomId", "is required");
        }
        if (!UUID.matcher(roomId).matches()) {
            throw ApiException.badParameter("roomId", "must be a UUID");
        }
        return roomId.toLowerCase(Locale.ROOT);
    }

    /** FR3 at the edge: default 50, 1..max (400). The repository re-checks (Step 16): belt and braces, not duplication. */
    private int parseLimit(String raw) {
        if (raw == null) {
            return defaultLimit;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw ApiException.badParameter("limit", "must be an integer");
        }
        if (limit < 1 || limit > maxLimit) {
            throw ApiException.badParameter("limit", "must be between 1 and " + maxLimit);
        }
        return limit;
    }
}
