package com.sahmhoot.room.repository;

import com.sahmhoot.room.entity.RoomParticipant;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomParticipantRepository extends JpaRepository<RoomParticipant, Long> {
  Optional<RoomParticipant> findByRoomIdAndUserIdAndLeftAtIsNull(Long roomId, Long userId);

  Optional<RoomParticipant> findByRoomIdAndUserId(Long roomId, Long userId);

  boolean existsByRoomIdAndNickname(Long roomId, String nickname);

  List<RoomParticipant> findByRoomIdAndUserIdInAndLeftAtIsNull(Long roomId, Set<Long> userIds);

  List<RoomParticipant> findByRoomIdInAndUserIdInAndLeftAtIsNull(Set<Long> roomIds, Set<Long> userIds);
}
