package com.sahmhoot.room;

import java.time.Instant;

public record RoomSummaryResponse(
    Long id, String title, RoomStatus status, String joinCode, int onlineCount, Instant createdAt) {
  public static RoomSummaryResponse from(Room room) {
    return new RoomSummaryResponse(room.getId(), room.getTitle(), room.getStatus(),
        room.getCode(), 0, room.getCreatedAt());
  }
}
