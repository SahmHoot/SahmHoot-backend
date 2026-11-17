package com.sahmhoot.chat.event;

import com.sahmhoot.chat.dto.ChatMessageResponse;
import java.time.Instant;

public record ChatMessageEvent(Long roomId, Payload payload) {
  public static ChatMessageEvent of(Long roomId, ChatMessageResponse message) {
    return new ChatMessageEvent(roomId, new Payload("MESSAGE", message.createdAt(), message.id(),
        message.senderParticipantId(), message.nickname(), message.isOwner(), message.content(),
        message.parentId(), message.parentPreview(), message.createdAt()));
  }

  public record Payload(String type, Instant sentAt, Long id, Long senderParticipantId,
      String nickname, boolean isOwner, String content, Long parentId, String parentPreview,
      Instant createdAt) {}
}
