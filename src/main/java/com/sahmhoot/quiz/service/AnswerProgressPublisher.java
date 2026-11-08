package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.message.AnswerProgressMessage;
import com.sahmhoot.quiz.dto.message.QuestionClosedMessage;
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import com.sahmhoot.quiz.schedule.AnswerTaskScheduler;
import com.sahmhoot.quiz.service.port.RoomLockPort;
import com.sahmhoot.room.service.port.RoomTaskCanceller;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 커밋된 응답 수만 교수에게 보낸다. 같은 방의 연속 갱신은 최소 0.5초 간격으로 최신 값을 묶는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerProgressPublisher implements RoomTaskCanceller {
  private static final Duration MIN_INTERVAL = Duration.ofMillis(500);
  private final RoomLockPort locks;
  private final AnswerTaskScheduler scheduler;
  private final SimpMessagingTemplate messaging;
  private final Clock clock;
  private final Map<Long, Progress> progressByRoom = new ConcurrentHashMap<>();
  private final Map<Long, Instant> lastAttemptByRoom = new ConcurrentHashMap<>();
  private volatile boolean stopping;

  /** 저장 트랜잭션이 커밋된 뒤 같은 방 잠금 안에서 호출한다. */
  public void publish(Long roomId, Long ownerId, AnswerProgressResponse response) {
    if (stopping) return;
    Progress progress = progressByRoom.compute(roomId, (id, current) ->
        current != null && current.questionId.equals(response.runQuestionId())
            ? current : new Progress(ownerId, response.runQuestionId(), lastAttemptByRoom.get(roomId)));
    if (progress.answeredCount == response.answeredCount()) return;
    progress.answeredCount = response.answeredCount();
    if (progress.future != null) return;

    Instant now = clock.instant();
    if (progress.lastAttemptAt == null || !now.isBefore(progress.lastAttemptAt.plus(MIN_INTERVAL))) {
      send(roomId, progress, now);
      return;
    }
    schedule(roomId, progress);
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onQuizChanged(QuizStateChangedEvent event) {
    // 마감 직전 집계는 마감 뒤에도 0.5초 간격으로 보낸다. 교수 화면은 3초 피드백 동안에도 응답 수를 표시한다.
    if (event.message() instanceof QuestionClosedMessage closed) {
      var progress = progressByRoom.get(event.roomId());
      if (progress != null && progress.questionId.equals(closed.runQuestionId())) return;
    }
    // 문항·회차가 바뀌어도 방의 전송 간격은 유지한다.
    cancelPending(event.roomId());
  }

  private void schedule(Long roomId, Progress progress) {
    if (stopping || progressByRoom.get(roomId) != progress) return;
    try {
      progress.future = scheduler.schedule(progress.lastAttemptAt.plus(MIN_INTERVAL),
          () -> locks.executeWithLock(roomId, () -> flush(roomId, progress)));
    } catch (RuntimeException failure) {
      log.error("Failed to schedule answer progress for roomId={}", roomId, failure);
    }
  }

  private void flush(Long roomId, Progress progress) {
    if (stopping || progressByRoom.get(roomId) != progress) return;
    progress.future = null;
    Instant now = clock.instant();
    if (now.isBefore(progress.lastAttemptAt.plus(MIN_INTERVAL))) {
      schedule(roomId, progress);
      return;
    }
    send(roomId, progress, now);
  }

  private void send(Long roomId, Progress progress, Instant now) {
    progress.lastAttemptAt = now;
    lastAttemptByRoom.put(roomId, now);
    try {
      messaging.convertAndSendToUser(progress.ownerId.toString(), "/queue/rooms/" + roomId + "/stats",
          AnswerProgressMessage.of(progress.questionId, progress.answeredCount, now));
    } catch (RuntimeException failure) {
      // 전송 실패가 이미 커밋된 답안이나 전원 응답 마감을 방해하지 않게 한다.
      log.error("Failed to send answer progress; retrying for roomId={}", roomId, failure);
      schedule(roomId, progress);
    }
  }

  @Override
  public void cancelAll(Long roomId) {
    cancelPending(roomId);
    lastAttemptByRoom.remove(roomId);
  }

  private void cancelPending(Long roomId) {
    var progress = progressByRoom.remove(roomId);
    if (progress != null && progress.future != null) progress.future.cancel(false);
  }

  @PreDestroy
  void shutdown() {
    stopping = true;
    progressByRoom.keySet().forEach(this::cancelAll);
    lastAttemptByRoom.clear();
  }

  /** 갱신과 지연 전송은 같은 방 잠금으로 직렬화한다. 상태 전환 후 콜백은 객체 동일성으로 걸러낸다. */
  private static final class Progress {
    private final Long ownerId;
    private final Long questionId;
    private int answeredCount;
    private Instant lastAttemptAt;
    private ScheduledFuture<?> future;

    private Progress(Long ownerId, Long questionId, Instant lastAttemptAt) {
      this.ownerId = ownerId;
      this.questionId = questionId;
      this.lastAttemptAt = lastAttemptAt;
    }
  }
}
