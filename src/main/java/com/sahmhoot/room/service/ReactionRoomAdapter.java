package com.sahmhoot.room.service;

import com.sahmhoot.reaction.service.port.ReactionRoomPort;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 반응 모듈이 정한 {@link ReactionRoomPort}의 구현. 방 엔티티 대신 필요한 값만 넘긴다. */
@Component
@RequiredArgsConstructor
public class ReactionRoomAdapter implements ReactionRoomPort {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;

  @Override
  public Optional<Room> findRoom(Long roomId) {
    return rooms.findById(roomId)
        .map(room -> new Room(room.getHostId(), room.getStatus() == RoomStatus.CLOSED));
  }

  @Override
  public boolean isActiveParticipant(Long roomId, Long userId) {
    return participants.findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId).isPresent();
  }
}
