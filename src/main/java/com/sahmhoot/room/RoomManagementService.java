package com.sahmhoot.room;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.service.QuizCoreTxService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.UserRepository;
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
  private final UserRepository users;
  private final RoomLockManager locks;
  private final QuizRunRepository runs;
  private final QuizCoreTxService quizzes;
  private final ObjectProvider<RoomTaskCanceller> cancellers;
  private final SimpMessagingTemplate messaging;
  private final TransactionTemplate closing;

  public RoomManagementService(RoomRepository rooms, UserRepository users, RoomLockManager locks,
      QuizRunRepository runs, QuizCoreTxService quizzes, ObjectProvider<RoomTaskCanceller> cancellers,
      SimpMessagingTemplate messaging, PlatformTransactionManager transactions) {
    this.rooms = rooms;
    this.users = users;
    this.locks = locks;
    this.runs = runs;
    this.quizzes = quizzes;
    this.cancellers = cancellers;
    this.messaging = messaging;
    this.closing = new TransactionTemplate(transactions);
    this.closing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Transactional(readOnly = true)
  public List<RoomSummaryResponse> mine(Long userId) {
    var user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    if (user.getRole() != Role.PROFESSOR) throw new ApiException(ErrorCode.FORBIDDEN);
    return rooms.findByHostIdAndStatusNotOrderByCreatedAtDescIdDesc(userId, RoomStatus.CLOSED)
        .stream().map(RoomSummaryResponse::from).toList();
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void close(Long roomId, Long userId) {
    locks.executeWithLock(roomId, () -> {
      Boolean changed = closing.execute(tx -> {
        Room room = rooms.findById(roomId).orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
        if (!room.getHostId().equals(userId)) throw new ApiException(ErrorCode.FORBIDDEN);
        if (room.getStatus() == RoomStatus.CLOSED) return false;
        runs.findTopByRoomIdOrderByIdDesc(roomId).filter(QuizRun::isRunning)
            .ifPresent(run -> quizzes.abortQuiz(roomId, run.getId(), userId));
        // abortQuiz가 JDBC로 OPEN을 기록한 뒤, 이 엔티티를 CLOSED로 변경해 최종 상태를 저장한다.
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
