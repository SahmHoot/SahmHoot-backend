package com.sahmhoot.chat.service.port;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 방의 내부 엔티티 대신 채팅에 필요한 참여 정보만 제공한다. */
public interface ChatRoomPort {
  Optional<Room> findRoom(Long roomId);
  Optional<Participant> findActiveParticipant(Long roomId, Long userId);
  Map<Long, Participant> findParticipants(Long roomId, Set<Long> participantIds);

  record Room(Long ownerId, boolean closed) {}
  record Participant(Long id, Long userId, String nickname) {}
}
