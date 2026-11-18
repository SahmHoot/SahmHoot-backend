package com.sahmhoot.chat.event;

import java.time.Instant;

public record ChatStatusChangedEvent(Long roomId, Long userId, Payload payload) {
  public static ChatStatusChangedEvent of(Long roomId, Long userId, Instant sentAt, boolean chatBlocked) {
    return new ChatStatusChangedEvent(roomId, userId, new Payload("MY_STATUS", sentAt, chatBlocked));
  }

  public record Payload(String type, Instant sentAt, boolean chatBlocked) {}
}
