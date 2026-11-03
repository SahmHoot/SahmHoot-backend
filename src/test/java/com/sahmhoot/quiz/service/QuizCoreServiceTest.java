package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizCoreServiceTest {

  private RoomLockManager roomLockManager;

  @Mock
  private QuizCoreTxService quizCoreTxService;

  /** 가짜 예약 도구. 무엇을 언제 예약했는지만 기록하고 실제로 실행하지 않는다. */
  @Mock
  private QuizTaskScheduler taskScheduler;

  private QuizCoreService quizCoreService;

  @BeforeEach
  void setUp() {
    roomLockManager = new RoomLockManager();
    quizCoreService = new QuizCoreService(roomLockManager, quizCoreTxService, taskScheduler);
  }

  @Test
  @DisplayName("startQuiz는 방 잠금 안에서 QuizCoreTxService.startQuiz를 호출하고 결과를 반환한다")
  void startQuiz_delegatesToTxService() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    StartQuizRequest request = new StartQuizRequest(7L);
    StartQuizResponse expectedResponse = new StartQuizResponse(77L, 5, 810L, Instant.now().plusSeconds(15));

    given(quizCoreTxService.startQuiz(roomId, request, hostUserId)).willReturn(expectedResponse);

    StartQuizResponse response = quizCoreService.startQuiz(roomId, request, hostUserId);

    assertThat(response).isEqualTo(expectedResponse);
    verify(quizCoreTxService).startQuiz(roomId, request, hostUserId);
  }

  @Test
  @DisplayName("abortQuiz는 방 잠금 안에서 QuizCoreTxService.abortQuiz를 호출한다")
  void abortQuiz_delegatesToTxService() {
    Long roomId = 42L;
    Long runId = 77L;
    Long hostUserId = 1L;

    quizCoreService.abortQuiz(roomId, runId, hostUserId);

    verify(quizCoreTxService).abortQuiz(roomId, runId, hostUserId);
  }

  @Test
  @DisplayName("closeResult는 방 잠금 안에서 QuizCoreTxService.closeResult를 호출한다")
  void closeResult_delegatesToTxService() {
    Long roomId = 42L;
    Long hostUserId = 1L;

    quizCoreService.closeResult(roomId, hostUserId);

    verify(quizCoreTxService).closeResult(roomId, hostUserId);
  }

  @Test
  @DisplayName("closeQuestion은 방 잠금 안에서 QuizCoreTxService.closeQuestion을 호출한다")
  void closeQuestion_lastQuestion_delegatesToTxService() {
    Long roomId = 42L;
    Long runQuestionId = 812L;
    CloseQuestionResult txResult = CloseQuestionResult.closed(
        77L,
        runQuestionId,
        3001L,
        true,
        null,
        Instant.now().plusSeconds(3),
        Instant.now()
    );

    given(quizCoreTxService.closeQuestion(roomId, runQuestionId)).willReturn(txResult);

    quizCoreService.closeQuestion(roomId, runQuestionId);

    verify(quizCoreTxService).closeQuestion(roomId, runQuestionId);
  }

  @Test
  @DisplayName("finishQuiz는 방 잠금 안에서 QuizCoreTxService.finishQuiz를 호출하여 결과를 집계한다")
  void finishQuiz_delegatesToTxService() {
    Long roomId = 42L;
    Long runId = 77L;
    QuizResultResponse expectedResponse = new QuizResultResponse(List.of());

    given(quizCoreTxService.finishQuiz(roomId, runId)).willReturn(expectedResponse);

    QuizResultResponse response = quizCoreService.finishQuiz(roomId, runId);

    assertThat(response).isEqualTo(expectedResponse);
    verify(quizCoreTxService).finishQuiz(roomId, runId);
  }

  @Test
  @DisplayName("openQuestion은 방 잠금 안에서 QuizCoreTxService.openQuestion을 호출한다")
  void openQuestion_delegatesToTxService() {
    Long roomId = 42L;
    Long runId = 77L;
    int orderNo = 2;
    given(quizCoreTxService.openQuestion(roomId, runId, orderNo)).willReturn(OpenQuestionResult.ignored());

    quizCoreService.openQuestion(roomId, runId, orderNo);

    verify(quizCoreTxService).openQuestion(roomId, runId, orderNo);
  }

  @Test
  @DisplayName("startQuiz는 커밋 뒤 1번 문항 마감을 closesAt + 1초에 한 번 예약한다")
  void startQuiz_schedulesFirstQuestionCloseAtClosesAtPlusOneSecond() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    StartQuizRequest request = new StartQuizRequest(7L);
    Instant closesAt = Instant.parse("2026-11-04T10:00:20Z");
    given(quizCoreTxService.startQuiz(roomId, request, hostUserId))
        .willReturn(new StartQuizResponse(77L, 5, 810L, closesAt));

    quizCoreService.startQuiz(roomId, request, hostUserId);

    // 정확히 한 번, 그 방에, 20초 + 1초 시각으로 예약됐는지
    verify(taskScheduler).schedule(eq(roomId), eq(Instant.parse("2026-11-04T10:00:21Z")), any(Runnable.class));
  }

  @Test
  @DisplayName("문항을 실제로 열면 그 문항 마감을 closesAt + 1초에 예약한다")
  void openQuestion_opened_schedulesCloseAtClosesAtPlusOneSecond() {
    Long roomId = 42L;
    Long runId = 77L;
    Instant closesAt = Instant.parse("2026-11-04T10:01:00Z");
    given(quizCoreTxService.openQuestion(roomId, runId, 2)).willReturn(OpenQuestionResult.opened(811L, closesAt));

    quizCoreService.openQuestion(roomId, runId, 2);

    verify(taskScheduler).schedule(eq(roomId), eq(Instant.parse("2026-11-04T10:01:01Z")), any(Runnable.class));
  }

  @Test
  @DisplayName("문항을 열지 않은 호출(이미 열림, 중단된 회차 등)은 마감을 예약하지 않는다")
  void openQuestion_ignored_doesNotSchedule() {
    given(quizCoreTxService.openQuestion(42L, 77L, 2)).willReturn(OpenQuestionResult.ignored());

    quizCoreService.openQuestion(42L, 77L, 2);

    verify(taskScheduler, never()).schedule(any(), any(), any());
  }

  @Test
  @DisplayName("예약된 마감이 실행되면 그 방·그 문항의 closeQuestion을 부른다")
  void scheduledClose_runsCloseQuestionForThatQuestion() {
    Long roomId = 42L;
    Long runId = 77L;
    given(quizCoreTxService.openQuestion(roomId, runId, 2))
        .willReturn(OpenQuestionResult.opened(811L, Instant.parse("2026-11-04T10:01:00Z")));
    given(quizCoreTxService.closeQuestion(roomId, 811L)).willReturn(CloseQuestionResult.ignored());
    quizCoreService.openQuestion(roomId, runId, 2);

    // 예약 시각이 된 것처럼 예약된 일을 직접 실행한다.
    capturedScheduledTask().run();

    verify(quizCoreTxService).closeQuestion(roomId, 811L);
  }

  @Test
  @DisplayName("마지막이 아닌 문항이 닫히면 nextOpensAt에 한 번 예약하고, 실행하면 다음 문항을 연다")
  void closeQuestion_notLast_schedulesNextQuestionAtNextOpensAt() {
    Long roomId = 42L;
    Instant nextOpensAt = Instant.parse("2026-11-04T10:00:24Z");
    given(quizCoreTxService.closeQuestion(roomId, 811L)).willReturn(CloseQuestionResult.closed(
        77L, 811L, 3001L, false, 2, nextOpensAt, Instant.parse("2026-11-04T10:00:21Z")));
    given(quizCoreTxService.openQuestion(roomId, 77L, 2)).willReturn(OpenQuestionResult.ignored());

    quizCoreService.closeQuestion(roomId, 811L);

    verify(taskScheduler).schedule(eq(roomId), eq(nextOpensAt), any(Runnable.class));
    capturedScheduledTask().run();
    verify(quizCoreTxService).openQuestion(roomId, 77L, 2);
    verify(quizCoreTxService, never()).finishQuiz(any(), any());
  }

  @Test
  @DisplayName("마지막 문항이 닫히면 nextOpensAt에 한 번 예약하고, 실행하면 결과 처리를 한다")
  void closeQuestion_last_schedulesFinishAtNextOpensAt() {
    Long roomId = 42L;
    Instant nextOpensAt = Instant.parse("2026-11-04T10:00:24Z");
    given(quizCoreTxService.closeQuestion(roomId, 812L)).willReturn(CloseQuestionResult.closed(
        77L, 812L, 3001L, true, null, nextOpensAt, Instant.parse("2026-11-04T10:00:21Z")));

    quizCoreService.closeQuestion(roomId, 812L);

    verify(taskScheduler).schedule(eq(roomId), eq(nextOpensAt), any(Runnable.class));
    capturedScheduledTask().run();
    verify(quizCoreTxService).finishQuiz(roomId, 77L);
    verify(quizCoreTxService, never()).openQuestion(any(), any(), anyInt());
  }

  @Test
  @DisplayName("닫지 않은 호출(이미 닫힘, 중단된 회차)은 다음 진행을 예약하지 않는다")
  void closeQuestion_ignored_doesNotSchedule() {
    given(quizCoreTxService.closeQuestion(42L, 811L)).willReturn(CloseQuestionResult.ignored());

    quizCoreService.closeQuestion(42L, 811L);

    verify(taskScheduler, never()).schedule(any(), any(), any());
  }

  @Test
  @DisplayName("닫힘 → 3초 뒤 다음 문항 열림 → 그 문항의 마감이 다시 예약되어 진행이 이어진다")
  void closeThenOpen_chainsNextClose() {
    Long roomId = 42L;
    Instant nextOpensAt = Instant.parse("2026-11-04T10:00:24Z");
    Instant secondClosesAt = Instant.parse("2026-11-04T10:00:44Z");
    given(quizCoreTxService.closeQuestion(roomId, 811L)).willReturn(CloseQuestionResult.closed(
        77L, 811L, 3001L, false, 2, nextOpensAt, Instant.parse("2026-11-04T10:00:21Z")));
    given(quizCoreTxService.openQuestion(roomId, 77L, 2))
        .willReturn(OpenQuestionResult.opened(812L, secondClosesAt));

    quizCoreService.closeQuestion(roomId, 811L);
    capturedScheduledTask().run();   // 3초 뒤: 2번 문항 열기

    ArgumentCaptor<Instant> runAts = ArgumentCaptor.forClass(Instant.class);
    verify(taskScheduler, times(2)).schedule(eq(roomId), runAts.capture(), any(Runnable.class));
    // 첫 예약은 다음 문항 열기, 두 번째는 2번 문항 마감(20초 + 1초)
    assertThat(runAts.getAllValues()).containsExactly(nextOpensAt, Instant.parse("2026-11-04T10:00:45Z"));
  }

  /** 예약 도구에 넘긴 "할 일"을 꺼낸다. */
  private Runnable capturedScheduledTask() {
    ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
    verify(taskScheduler).schedule(any(), any(), task.capture());
    return task.getValue();
  }
}
