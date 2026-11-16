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
public class ChatRestrictionId implements Serializable {
  @Column(name = "room_id", nullable = false)
  private Long roomId;
  @Column(name = "participant_id", nullable = false)
  private Long participantId;

  public ChatRestrictionId(Long roomId, Long participantId) {
    this.roomId = roomId;
    this.participantId = participantId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof ChatRestrictionId that)) return false;
    return Objects.equals(roomId, that.roomId) && Objects.equals(participantId, that.participantId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(roomId, participantId);
  }
}
