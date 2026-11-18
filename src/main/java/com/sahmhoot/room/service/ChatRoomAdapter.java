package com.sahmhoot.room.service;

import com.sahmhoot.chat.service.port.ChatRoomPort;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatRoomAdapter implements ChatRoomPort {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;

  @Override
  public Optional<Room> findRoom(Long roomId) {
    return rooms.findById(roomId)
        .map(room -> new Room(room.getHostId(), room.getStatus() == RoomStatus.CLOSED));
  }

  @Override
  public Optional<Participant> findActiveParticipant(Long roomId, Long userId) {
    return participants.findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
        .map(p -> new Participant(p.getId(), p.getUserId(), p.getNickname()));
  }

  @Override
  public Map<Long, Participant> findParticipants(Long roomId, Set<Long> participantIds) {
    if (participantIds.isEmpty()) return Map.of();
    // 퇴장한 작성자의 과거 메시지도 닉네임으로 복구한다.
    return participants.findByRoomIdAndIdIn(roomId, participantIds).stream()
        .collect(Collectors.toMap(p -> p.getId(),
            p -> new Participant(p.getId(), p.getUserId(), p.getNickname())));
  }
}
