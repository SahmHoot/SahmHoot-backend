package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import com.sahmhoot.quiz.service.QuizRecoveryReader.Question;
import com.sahmhoot.quiz.service.QuizRecoveryReader.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 기동 시 DB에 남은 RUNNING 회차를 같은 방 잠금·진행 서비스로 이어 간다(#25). */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuizRecoveryService implements ApplicationRunner {
  private final QuizRecoveryReader reader;
  private final RoomLockManager locks;
  private final QuizCoreService core;
  private final QuizTaskScheduler scheduler;
  private final QuizFeedbackState feedback;
  private final Clock clock;

  @Override
  public void run(ApplicationArguments args) {
    recoverRunningQuizzes();
  }

  public void recoverRunningQuizzes() {
    for (var candidate : reader.findRunningQuizzes()) {
      try {
        locks.executeWithLock(candidate.roomId(), () -> reader.read(candidate).ifPresent(this::restore));
      } catch (RuntimeException failure) {
        // 한 회차의 잘못된 기록이 다른 수업의 예약 복구를 막지 않게 한다.
        log.error("Failed to recover quiz roomId={}, runId={}", candidate.roomId(), candidate.runId(), failure);
      }
    }
  }

  private void restore(Snapshot snapshot) {
    var questions = snapshot.questions();
    if (questions.isEmpty()) throw new IllegalStateException("Running quiz has no questions");

    int index = 0;
    while (index < questions.size() && questions.get(index).status() == RunQuestionStatus.CLOSED) index++;
    if (index == questions.size()) {
      resumeAfterFeedback(snapshot, questions.getLast(), () -> core.finishQuiz(snapshot.roomId(), snapshot.runId()));
      return;
    }

    Question current = questions.get(index);
    if (questions.subList(index + 1, questions.size()).stream()
        .anyMatch(question -> question.status() != RunQuestionStatus.READY)) {
      throw new IllegalStateException("Running quiz has inconsistent question order");
    }
    if (current.status() == RunQuestionStatus.OPEN) {
      // 정상 진행과 동일하게 closesAt + 1초까지 답안을 받는다.
      Instant closeAt = Objects.requireNonNull(current.closesAt(), "Open question needs closesAt").plusSeconds(1);
      resume(snapshot.roomId(), closeAt, () -> core.closeQuestion(snapshot.roomId(), current.id()));
      return;
    }
    if (index == 0) throw new IllegalStateException("Running quiz has no opened or closed question");

    resumeAfterFeedback(snapshot, questions.get(index - 1),
        () -> core.openQuestion(snapshot.roomId(), snapshot.runId(), current.orderNo()));
  }

  private void resumeAfterFeedback(Snapshot snapshot, Question closed, Runnable action) {
    Instant closedAt = Objects.requireNonNull(closed.closedAt(), "Closed question needs closedAt");
    Instant opensAt = feedback.nextOpensAt(snapshot.roomId(), snapshot.runId(), closed.id())
        .orElseGet(() -> closedAt.plusSeconds(3));
    resume(snapshot.roomId(), opensAt, action);
  }

  private void resume(Long roomId, Instant runAt, Runnable action) {
    // 같은 회차를 다시 확인해도 예약은 하나만 남긴다. 최신 회차 검증 뒤에만 취소한다.
    scheduler.cancelAll(roomId);
    if (!runAt.isAfter(Instant.now(clock))) {
      action.run();
      return;
    }
    scheduler.schedule(roomId, runAt, action);
  }
}
