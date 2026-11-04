package com.sahmhoot.room.service;

import com.sahmhoot.common.ratelimit.RateLimiter;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.JoinRoomRequest;
import com.sahmhoot.room.dto.JoinRoomResponse;
import com.sahmhoot.room.dto.RoomByCodeResponse;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RoomEntryService {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final RoomUserLookup users;
  private final RateLimiter limiter;
  private final RoomLockManager locks;
  private final TransactionTemplate joining;

  public RoomEntryService(RoomRepository rooms, RoomParticipantRepository participants,
      RoomUserLookup users, RateLimiter limiter, RoomLockManager locks,
      PlatformTransactionManager transactions) {
    this.rooms = rooms;
    this.participants = participants;
    this.users = users;
    this.limiter = limiter;
    this.locks = locks;
    this.joining = new TransactionTemplate(transactions);
    this.joining.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public RoomByCodeResponse byCode(Long userId, String joinCode) {
    checkAccess(userId);
    Room room = openRoom(joinCode);
    var participant = participants.findByRoomIdAndUserId(room.getId(), userId);
    var owner = users.findById(room.getHostId())
        .orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
    return new RoomByCodeResponse(room.getId(), room.getTitle(), owner.name(), room.getStatus(),
        participant.isPresent(), room.getHostId().equals(userId),
        participant.map(RoomParticipant::getNickname).orElse(null));
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public EntryResult join(Long userId, String joinCode, JoinRoomRequest request) {
    checkAccess(userId);
    Long roomId = openRoom(joinCode).getId();
    // 종료와 같은 방 잠금을 공유하고, 잠금을 잡은 후 시작한 트랜잭션에서 상태를 다시 읽는다.
    return locks.executeWithLock(roomId, () -> joining.execute(tx -> {
      Room room = rooms.findById(roomId)
          .filter(found -> found.getStatus() != RoomStatus.CLOSED)
          .orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
      var existing = participants.findByRoomIdAndUserId(roomId, userId);
      if (existing.isPresent()) {
        existing.get().rejoin();
        return new EntryResult(JoinRoomResponse.from(existing.get()), false);
      }
      if (request.nickname().contains("교수")) {
        throw new RoomException(RoomErrorCode.NICKNAME_NOT_ALLOWED);
      }
      if (participants.existsByRoomIdAndNickname(roomId, request.nickname())) {
        throw new RoomException(RoomErrorCode.NICKNAME_ALREADY_USED);
      }
      RoomParticipant participant = participants.saveAndFlush(
          new RoomParticipant(room.getId(), userId, request.nickname()));
      return new EntryResult(JoinRoomResponse.from(participant), true);
    }));
  }

  private void checkAccess(Long userId) {
    if (users.findById(userId).isEmpty()) throw new RoomException(RoomErrorCode.UNAUTHORIZED);
    // 조회와 입장이 같은 키를 사용하므로 방·API가 달라도 사용자별 합산한다.
    if (!limiter.tryAcquire("join:" + userId, 20, Duration.ofMinutes(1))) {
      throw new RoomException(RoomErrorCode.RATE_LIMITED);
    }
  }

  private Room openRoom(String joinCode) {
    return rooms.findByCode(joinCode).filter(room -> room.getStatus() != RoomStatus.CLOSED)
        .orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
  }

  public record EntryResult(JoinRoomResponse response, boolean created) {}
}
