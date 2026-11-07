package com.sahmhoot.room.service;

import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** #65의 사용자 집합을 방의 활성 비소유 참여자 ID로 변환한다. 세션 추적은 수행하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomPresenceService implements ParticipantPresencePort {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final RoomPresencePort source;

  @Override
  public Set<Long> getOnlineParticipantIds(Long roomId) {
    Room room = rooms.findById(roomId).orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
    if (room.getStatus() == RoomStatus.CLOSED) throw new RoomException(RoomErrorCode.ROOM_CLOSED);
    return ids(room, snapshot(roomId));
  }

  public int onlineCount(Room room) {
    return members(room, snapshot(room.getId()).onlineUserIds()).size();
  }

  public Map<Long, Integer> onlineCounts(List<Room> roomList) {
    var counts = new HashMap<Long, Integer>();
    var onlineByRoom = new HashMap<Long, Set<Long>>();
    var onlineUsers = new HashSet<Long>();
    for (Room room : roomList) {
      counts.put(room.getId(), 0);
      var online = new HashSet<>(snapshot(room.getId()).onlineUserIds());
      online.remove(room.getHostId());
      if (!online.isEmpty()) {
        onlineByRoom.put(room.getId(), online);
        onlineUsers.addAll(online);
      }
    }
    if (!onlineByRoom.isEmpty()) {
      var active = participants.findByRoomIdInAndUserIdInAndLeftAtIsNull(onlineByRoom.keySet(), onlineUsers);
      for (RoomParticipant participant : active) {
        // 일괄 쿼리의 사용자 합집합에는 다른 방에서만 접속한 사용자도 들어 있다.
        if (onlineByRoom.get(participant.getRoomId()).contains(participant.getUserId())) {
          counts.merge(participant.getRoomId(), 1, Integer::sum);
        }
      }
    }
    return Map.copyOf(counts);
  }

  public Optional<CountChange> countChange(RoomPresenceSnapshot previous, Integer knownCount) {
    var room = rooms.findById(previous.roomId()).filter(value -> value.getStatus() != RoomStatus.CLOSED);
    if (room.isEmpty()) return Optional.empty();
    var current = snapshot(previous.roomId());
    var candidates = new HashSet<>(current.onlineUserIds());
    if (knownCount == null) candidates.addAll(previous.onlineUserIds());
    var active = members(room.get(), candidates);
    int before = knownCount != null ? knownCount : count(active, previous.onlineUserIds());
    return Optional.of(new CountChange(before, count(active, current.onlineUserIds())));
  }

  private RoomPresenceSnapshot snapshot(Long roomId) {
    var snapshot = Objects.requireNonNull(source.getPresence(roomId), "Presence snapshot is required");
    if (!roomId.equals(snapshot.roomId())) throw new IllegalStateException("접속 스냅샷의 방 번호가 다릅니다.");
    return snapshot;
  }

  private Set<Long> ids(Room room, RoomPresenceSnapshot snapshot) {
    return members(room, snapshot.onlineUserIds()).stream()
        .map(participant -> participant.getId()).collect(Collectors.toUnmodifiableSet());
  }

  private List<RoomParticipant> members(Room room, Set<Long> userIds) {
    if (userIds.isEmpty()) return List.of();
    return participants.findByRoomIdAndUserIdInAndLeftAtIsNull(room.getId(), userIds).stream()
        .filter(participant -> !room.getHostId().equals(participant.getUserId())).toList();
  }

  private int count(List<RoomParticipant> active, Set<Long> userIds) {
    return (int) active.stream().filter(participant -> userIds.contains(participant.getUserId())).count();
  }

  public record CountChange(int previous, int current) {}
}
