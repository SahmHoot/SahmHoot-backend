package com.sahmhoot.chat.event;

import com.sahmhoot.chat.dto.ChatMessageResponse;
import java.time.Instant;

public record NoticePinnedEvent(Long roomId, Payload payload) {
  public static NoticePinnedEvent of(Long roomId, ChatMessageResponse message, Instant sentAt) {
    return new NoticePinnedEvent(roomId, new Payload("NOTICE_PINNED", sentAt, message));
  }

  public record Payload(String type, Instant sentAt, ChatMessageResponse message) {}
}
