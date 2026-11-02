package com.sahmhoot.room.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.RoomSummaryResponse;
import com.sahmhoot.room.dto.message.RoomEndedMessage;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.port.RoomQuizAbortPort;
import com.sahmhoot.room.service.port.RoomTaskCanceller;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class RoomManagementService {
  private final RoomRepository rooms;
  private final RoomUserLookup users;
  private final RoomLockManager locks;
  private final RoomQuizAbortPort quizzes;
  private final ObjectProvider<RoomTaskCanceller> cancellers;
  private final SimpMessagingTemplate messaging;
  private final TransactionTemplate closing;

  public RoomManagementService(RoomRepository rooms, RoomUserLookup users, RoomLockManager locks,
      RoomQuizAbortPort quizzes, ObjectProvider<RoomTaskCanceller> cancellers,
      SimpMessagingTemplate messaging, PlatformTransactionManager transactions) {
    this.rooms = rooms;
    this.users = users;
    this.locks = locks;
    this.quizzes = quizzes;
    this.cancellers = cancellers;
    this.messaging = messaging;
    this.closing = new TransactionTemplate(transactions);
    this.closing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public List<RoomSummaryResponse> mine(Long userId) {
    var user = users.findById(userId).orElseThrow(() -> new RoomException(RoomErrorCode.UNAUTHORIZED));
    if (!"PROFESSOR".equals(user.role())) throw new RoomException(RoomErrorCode.FORBIDDEN);
    return rooms.findByHostIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, RoomStatus.CLOSED)
        .stream().map(RoomSummaryResponse::from).toList();
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void close(Long roomId, Long userId) {
    locks.executeWithLock(roomId, () -> {
      Boolean changed = closing.execute(tx -> {
        Room room = rooms.findById(roomId).orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
        if (!room.getHostId().equals(userId)) throw new RoomException(RoomErrorCode.FORBIDDEN);
        if (room.getStatus() == RoomStatus.CLOSED) return false;
        quizzes.abortActiveQuiz(roomId);
        room.close(Instant.now());
        rooms.saveAndFlush(room);
        return true;
      });
      if (!Boolean.TRUE.equals(changed)) return;
      // 전용 트랜잭션의 커밋·리소스 정리가 끝난 뒤에도 방 잠금은 계속 유지한다.
      cancellers.orderedStream().forEach(canceller -> {
        try {
          canceller.cancelAll(roomId);
        } catch (RuntimeException error) {
          log.error("Room task cancellation failed: roomId={}, canceller={}",
              roomId, canceller.getClass().getName(), error);
        }
      });
      try {
        messaging.convertAndSend("/topic/rooms/" + roomId + "/quiz", RoomEndedMessage.now());
      } catch (RuntimeException error) {
        log.error("Room ended notification failed: roomId={}", roomId, error);
      }
    });
  }
}
