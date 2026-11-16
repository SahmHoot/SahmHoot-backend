package com.sahmhoot.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_pins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatPin {
  @EmbeddedId
  private ChatPinId id;
  @Column(name = "pinned_by_user_id", nullable = false)
  private Long pinnedByUserId;
  @Column(name = "pinned_at", nullable = false)
  private Instant pinnedAt;

  public ChatPin(ChatPinId id, Long pinnedByUserId, Instant pinnedAt) {
    this.id = id;
    this.pinnedByUserId = pinnedByUserId;
    this.pinnedAt = pinnedAt;
  }
}
