package com.sahmhoot.room.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "host_id", nullable = false)
  private Long hostId;
  @Column(nullable = false, length = 50)
  private String title;
  @Column(nullable = false, unique = true, length = 6)
  private String code;
  @Enumerated(EnumType.STRING) @Column(nullable = false)
  private RoomStatus status;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
  @Column(name = "closed_at")
  private Instant closedAt;

  public Room(Long hostId, String title, String code) {
    this.hostId = hostId;
    this.title = title;
    this.code = code;
    this.status = RoomStatus.OPEN;
    this.createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  }

  /** 퀴즈 시작: 대기 중인 방만 진행 상태가 된다. */
  public void startPlaying() {
    if (status != RoomStatus.OPEN) throw new IllegalStateException("대기 중인 방만 퀴즈를 시작할 수 있다: " + status);
    status = RoomStatus.PLAYING;
  }

  /** 퀴즈 중단·결과 닫기: 진행 중인 방만 대기 상태로 돌아간다. */
  public void returnToWaiting() {
    if (status != RoomStatus.PLAYING) throw new IllegalStateException("진행 중인 방만 대기로 돌아갈 수 있다: " + status);
    status = RoomStatus.OPEN;
  }

  public void close(Instant now) {
    if (status == RoomStatus.CLOSED) return;
    status = RoomStatus.CLOSED;
    closedAt = now.truncatedTo(ChronoUnit.SECONDS);
  }
}
