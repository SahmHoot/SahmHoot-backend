package com.sahmhoot.room.dto.message;

import java.time.Instant;

public record RoomEndedMessage(String type, Instant sentAt) {
  public static RoomEndedMessage now() {
    return new RoomEndedMessage("ROOM_ENDED", Instant.now());
  }
}
