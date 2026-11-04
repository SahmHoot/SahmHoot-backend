package com.sahmhoot.room.dto;

import com.sahmhoot.room.entity.RoomParticipant;

public record JoinRoomResponse(Long roomId, Long participantId, String nickname) {
  public static JoinRoomResponse from(RoomParticipant participant) {
    return new JoinRoomResponse(participant.getRoomId(), participant.getId(), participant.getNickname());
  }
}
