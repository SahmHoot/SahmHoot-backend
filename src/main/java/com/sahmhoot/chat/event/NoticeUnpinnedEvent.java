package com.sahmhoot.chat.event;

import java.time.Instant;

public record NoticeUnpinnedEvent(Long roomId, Payload payload) {
  public static NoticeUnpinnedEvent of(Long roomId, Long messageId, Instant sentAt) {
    return new NoticeUnpinnedEvent(roomId, new Payload("NOTICE_UNPINNED", sentAt, messageId));
  }

  public record Payload(String type, Instant sentAt, Long messageId) {}
}
