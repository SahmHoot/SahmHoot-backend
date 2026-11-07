package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.schedule.ManualQuizTaskScheduler;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.quiz.service.QuizRecoveryReader.Question;
import com.sahmhoot.quiz.service.QuizRecoveryReader.RunningQuiz;
import com.sahmhoot.quiz.service.QuizRecoveryReader.Snapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class QuizRecoveryServiceTest {
  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
  private static final RunningQuiz RUN = new RunningQuiz(42L, 77L);

  @Mock QuizRecoveryReader reader;
  @Mock QuizCoreService core;
  private MutableClock clock;
  private ManualQuizTaskScheduler scheduler;
  private RoomLockManager locks;
  private QuizRecoveryService recovery;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    locks = new RoomLockManager();
    scheduler = new ManualQuizTaskScheduler(clock) {
      @Override
      public synchronized void schedule(Long roomId, Instant runAt, Runnable task) {
        assertThat(locks.isLocked(roomId)).isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        super.schedule(roomId, runAt, task);
      }
    };
    recovery = new QuizRecoveryService(reader, locks, core, scheduler, new QuizFeedbackState(), clock);
  }

  @Test
  @DisplayName("기동 시 진행 중인 회차가 없으면 예약과 상태 변경을 하지 않는다")
  void noRunningQuiz() {
    recovery.run(new DefaultApplicationArguments());

    assertThat(scheduler.pendingCount()).isZero();
    verifyNoInteractions(core);
  }

  @Test
  @DisplayName("기동 시 시간이 남은 문항은 기존 closesAt + 1초에 마감을 다시 예약한다")
  void restoresFutureClose() {
    givenRunning(open(810L, 1, T0.plusSeconds(20)), ready(811L, 3));

    recovery.run(new DefaultApplicationArguments());

    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(21));
    verifyNoInteractions(core);
    scheduler.runNext();
    verify(core).closeQuestion(42L, 810L);
  }

  @Test
  @DisplayName("마감 유예 시각까지 지난 문항은 기동 복구 안에서 즉시 마감한다")
  void closesExpiredQuestionImmediately() {
    givenRunning(open(810L, 1, T0.minusSeconds(10)));

    recovery.recoverRunningQuizzes();

    verify(core).closeQuestion(42L, 810L);
    assertThat(scheduler.pendingCount()).isZero();
  }

  @Test
  @DisplayName("closesAt + 1초와 현재 시각이 같아도 즉시 마감한다")
  void closesAtExactDeadline() {
    givenRunning(open(810L, 1, T0.minusSeconds(1)));

    recovery.recoverRunningQuizzes();

    verify(core).closeQuestion(42L, 810L);
  }

  @Test
  @DisplayName("closesAt에 재시작해도 정상 진행의 1초 유예를 줄이지 않는다")
  void preservesAnswerGracePeriod() {
    givenRunning(open(810L, 1, T0));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(1));
    verifyNoInteractions(core);
  }

  @Test
  @DisplayName("피드백 시간이 남으면 closedAt + 3초에 다음 문항의 실제 orderNo를 예약한다")
  void restoresNextQuestionAfterFeedback() {
    givenRunning(closed(810L, 2, T0.minusSeconds(1)), ready(811L, 7));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(2));
    scheduler.runNext();
    verify(core).openQuestion(42L, 77L, 7);
  }

  @Test
  @DisplayName("피드백 시간이 지났으면 다음 문항을 즉시 연다")
  void opensOverdueNextQuestion() {
    givenRunning(closed(810L, 1, T0.minusSeconds(20)), ready(811L, 3));

    recovery.recoverRunningQuizzes();

    verify(core).openQuestion(42L, 77L, 3);
    assertThat(scheduler.pendingCount()).isZero();
  }

  @Test
  @DisplayName("피드백 종료와 현재 시각이 같으면 다음 문항을 즉시 연다")
  void opensNextAtExactDeadline() {
    givenRunning(closed(810L, 1, T0.minusSeconds(3)), ready(811L, 2));

    recovery.recoverRunningQuizzes();

    verify(core).openQuestion(42L, 77L, 2);
  }

  @Test
  @DisplayName("마지막 문항 피드백이 남으면 결과 집계를 남은 시각에 예약한다")
  void restoresResultAfterLastFeedback() {
    givenRunning(closed(810L, 1, T0.minusSeconds(1)));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(2));
    scheduler.runNext();
    verify(core).finishQuiz(42L, 77L);
  }

  @Test
  @DisplayName("마지막 문항 피드백도 지났으면 결과를 즉시 집계한다")
  void finishesOverdueQuizImmediately() {
    givenRunning(closed(810L, 1, T0.minusSeconds(20)));

    recovery.recoverRunningQuizzes();

    verify(core).finishQuiz(42L, 77L);
    verify(core, never()).closeQuestion(42L, 810L);
  }

  @Test
  @DisplayName("후보 조회 뒤 이미 중단·종료됐거나 새 회차로 바뀌면 기존 예약을 건드리지 않는다")
  void skipsStaleCandidate() {
    given(reader.findRunningQuizzes()).willReturn(List.of(RUN));
    given(reader.read(RUN)).willReturn(Optional.empty());
    locks.executeWithLock(42L, () -> scheduler.schedule(42L, T0.plusSeconds(30), () -> {}));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(30));
    verifyNoInteractions(core);
  }

  @Test
  @DisplayName("같은 열린 문항을 반복 복구해도 마감 예약은 하나만 남는다")
  void replacesDuplicateSchedules() {
    givenRunning(open(810L, 1, T0.plusSeconds(20)));

    recovery.recoverRunningQuizzes();
    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingCount()).isOne();
  }

  @Test
  @DisplayName("다른 방의 예약은 유지하고 복구 대상 방의 예약만 교체한다")
  void preservesOtherRooms() {
    givenRunning(open(810L, 1, T0.plusSeconds(20)));
    locks.executeWithLock(43L, () -> scheduler.schedule(43L, T0.plusSeconds(30), () -> {}));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingCount(42L)).isOne();
    assertThat(scheduler.pendingCount(43L)).isOne();
  }

  @Test
  @DisplayName("문항 순서가 잘못된 회차를 건너뛰고 다른 방의 복구를 계속한다")
  void continuesAfterInvalidQuiz() {
    var other = new RunningQuiz(43L, 78L);
    given(reader.findRunningQuizzes()).willReturn(List.of(RUN, other));
    given(reader.read(RUN)).willReturn(Optional.of(new Snapshot(42L, 77L,
        List.of(ready(810L, 1), open(811L, 2, T0.plusSeconds(10))))));
    given(reader.read(other)).willReturn(Optional.of(new Snapshot(43L, 78L,
        List.of(open(812L, 1, T0.plusSeconds(20))))));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingCount(42L)).isZero();
    assertThat(scheduler.pendingCount(43L)).isOne();
    verifyNoInteractions(core);
  }

  @Test
  @DisplayName("마감 예정 시각이 없는 열린 문항에 임의의 새 제한 시간을 부여하지 않는다")
  void rejectsMissingDeadline() {
    givenRunning(open(810L, 1, null));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingCount()).isZero();
    verifyNoInteractions(core);
  }

  @Test
  @DisplayName("실제 마감 시각이 없는 문항은 피드백을 건너뛰거나 다음 문항을 열지 않는다")
  void rejectsMissingClosedAt() {
    givenRunning(closed(810L, 1, null), ready(811L, 2));

    recovery.recoverRunningQuizzes();

    assertThat(scheduler.pendingCount()).isZero();
    verifyNoInteractions(core);
  }

  private void givenRunning(Question... questions) {
    given(reader.findRunningQuizzes()).willReturn(List.of(RUN));
    given(reader.read(RUN)).willAnswer(invocation -> {
      assertThat(locks.isLocked(RUN.roomId())).isTrue();
      return Optional.of(new Snapshot(RUN.roomId(), RUN.runId(), List.of(questions)));
    });
  }

  private Question open(Long id, int order, Instant closesAt) {
    return new Question(id, order, RunQuestionStatus.OPEN, closesAt, null);
  }

  private Question ready(Long id, int order) {
    return new Question(id, order, RunQuestionStatus.READY, null, null);
  }

  private Question closed(Long id, int order, Instant closedAt) {
    return new Question(id, order, RunQuestionStatus.CLOSED, closedAt, closedAt);
  }
}
