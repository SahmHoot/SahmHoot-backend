package com.sahmhoot.room.service;

import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** #65의 사용자 집합을 방의 활성 비소유 참여자 ID로 변환한다. 세션 추적은 수행하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomPresenceService implements ParticipantPresencePort {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final ObjectProvider<RoomPresencePort> sources;

  @Override
  public Set<Long> getOnlineParticipantIds(Long roomId) {
    Room room = rooms.findById(roomId).orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
    if (room.getStatus() == RoomStatus.CLOSED) throw new RoomException(RoomErrorCode.ROOM_CLOSED);
    // #28은 #65 구현이 연결된 후 소비한다. 미구현을 '접속자 없음'으로 바꾸지 않는다.
    return ids(room, snapshot(sources.getObject(), roomId));
  }

  public int onlineCount(Room room) {
    // #65 병합 전 기존 REST의 임시 0을 유지한다. 퀴즈용 포트에는 이 대체 값을 사용하지 않는다.
    var source = sources.getIfAvailable();
    return source == null ? 0 : ids(room, snapshot(source, room.getId())).size();
  }

  public Optional<CountChange> countChange(RoomPresenceSnapshot previous) {
    var room = rooms.findById(previous.roomId()).filter(value -> value.getStatus() != RoomStatus.CLOSED);
    if (room.isEmpty()) return Optional.empty();
    var current = snapshot(sources.getObject(), previous.roomId());
    return Optional.of(new CountChange(ids(room.get(), previous).size(), ids(room.get(), current).size()));
  }

  private RoomPresenceSnapshot snapshot(RoomPresencePort source, Long roomId) {
    var snapshot = Objects.requireNonNull(source.getPresence(roomId), "Presence snapshot is required");
    if (!roomId.equals(snapshot.roomId())) throw new IllegalStateException("접속 스냅샷의 방 번호가 다릅니다.");
    return snapshot;
  }

  private Set<Long> ids(Room room, RoomPresenceSnapshot snapshot) {
    if (snapshot.onlineUserIds().isEmpty()) return Set.of();
    return participants.findByRoomIdAndUserIdInAndLeftAtIsNull(room.getId(), snapshot.onlineUserIds()).stream()
        .filter(participant -> !room.getHostId().equals(participant.getUserId()))
        .map(participant -> participant.getId()).collect(Collectors.toUnmodifiableSet());
  }

  public record CountChange(int previous, int current) {}
}
