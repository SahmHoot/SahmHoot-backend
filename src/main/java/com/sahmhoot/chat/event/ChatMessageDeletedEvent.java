package com.sahmhoot.chat.event;

import java.time.Instant;

public record ChatMessageDeletedEvent(Long roomId, Payload payload) {
  public static ChatMessageDeletedEvent of(Long roomId, Long messageId, Instant sentAt) {
    return new ChatMessageDeletedEvent(roomId, new Payload("MESSAGE_DELETED", sentAt, messageId));
  }

  public record Payload(String type, Instant sentAt, Long messageId) {}
}
