package com.sahmhoot.reaction.dto.message;

import java.time.Instant;

/** /topic/rooms/{roomId}/reaction에 보내는 반응 하나. 익명이라 보낸 사람 정보는 넣지 않는다. */
public record ReactionMessage(String type, Instant sentAt, String emoji) {
  public static ReactionMessage of(String emoji, Instant sentAt) {
    return new ReactionMessage("REACTION", sentAt, emoji);
  }
}
