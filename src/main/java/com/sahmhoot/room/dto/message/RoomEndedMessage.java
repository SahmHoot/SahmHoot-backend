package com.sahmhoot.room.dto.message;

import java.time.Instant;

public record RoomEndedMessage(String type, Instant sentAt) {
  public static RoomEndedMessage at(Instant sentAt) {
    return new RoomEndedMessage("ROOM_ENDED", sentAt);
  }
}
