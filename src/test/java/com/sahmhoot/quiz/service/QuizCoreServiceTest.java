package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizCoreServiceTest {

  private RoomLockManager roomLockManager;

  @Mock
  private QuizCoreTxService quizCoreTxService;

  private QuizCoreService quizCoreService;

  @BeforeEach
  void setUp() {
    roomLockManager = new RoomLockManager();
    quizCoreService = new QuizCoreService(roomLockManager, quizCoreTxService);
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

    quizCoreService.openQuestion(roomId, runId, orderNo);

    verify(quizCoreTxService).openQuestion(roomId, runId, orderNo);
  }
}
