package com.sahmhoot.chat.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "chat_restrictions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRestriction {
  @EmbeddedId
  private ChatRestrictionId id;
  @Column(name = "restricted_by_user_id", nullable = false)
  private Long restrictedByUserId;
  @Column(name = "restricted_at", nullable = false)
  private Instant restrictedAt;

  public ChatRestriction(ChatRestrictionId id, Long restrictedByUserId, Instant restrictedAt) {
    this.id = id;
    this.restrictedByUserId = restrictedByUserId;
    this.restrictedAt = restrictedAt;
  }
}
