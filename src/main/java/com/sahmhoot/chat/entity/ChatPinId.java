package com.sahmhoot.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatPinId implements Serializable {
  @Column(name = "room_id", nullable = false)
  private Long roomId;
  @Column(name = "message_id", nullable = false)
  private Long messageId;

  public ChatPinId(Long roomId, Long messageId) {
    this.roomId = roomId;
    this.messageId = messageId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ChatPinId that)) return false;
    return Objects.equals(roomId, that.roomId) && Objects.equals(messageId, that.messageId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(roomId, messageId);
  }
}
