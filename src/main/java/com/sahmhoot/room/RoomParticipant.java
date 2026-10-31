package com.sahmhoot.room;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "room_participants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomParticipant {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "room_id", nullable = false)
  private Long roomId;
  @Column(name = "user_id", nullable = false)
  private Long userId;
  @Column(nullable = false, length = 50)
  private String nickname;
  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;
  @Column(name = "left_at")
  private Instant leftAt;

  public RoomParticipant(Long roomId, Long userId, String nickname) {
    this.roomId = roomId;
    this.userId = userId;
    this.nickname = nickname;
    this.joinedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  }
}
