package com.sahmhoot.room;

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
}
