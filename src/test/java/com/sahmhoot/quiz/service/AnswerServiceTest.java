package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

  private RoomLockManager roomLockManager;

  @Mock
  private AnswerTxService answerTxService;

  @Mock
  private QuizCoreService quizCoreService;

  private AnswerService answerService;

  @BeforeEach
  void setUp() {
    roomLockManager = new RoomLockManager();
    answerService = new AnswerService(
        roomLockManager,
        answerTxService,
        quizCoreService
    );
  }

  @Test
  @DisplayName("submitAnswer는 방 잠금 안에서 AnswerTxService를 호출하고 AnswerProgressResponse를 반환한다")
  void submitAnswer_delegatesToTxService() {
    Long roomId = 42L;
    Long participantId = 900L;
    Long runQuestionId = 812L;
    Long choiceId = 3001L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(runQuestionId, choiceId);
    SubmitAnswerTxResult txResult = new SubmitAnswerTxResult(runQuestionId, 2, false);

    Set<Long> onlineParticipantIds = Set.of(participantId, 901L, 902L, 903L, 904L);
    given(answerTxService.submitAnswer(roomId, participantId, request, onlineParticipantIds)).willReturn(txResult);

    AnswerProgressResponse response = answerService.submitAnswer(roomId, participantId, request, onlineParticipantIds);

    assertThat(response.runQuestionId()).isEqualTo(runQuestionId);
    assertThat(response.answeredCount()).isEqualTo(2);
    verify(answerTxService).submitAnswer(roomId, participantId, request, onlineParticipantIds);
    verify(quizCoreService, never()).closeQuestion(any(), any());
  }

  @Test
  @DisplayName("전원 응답 조건 충족 시(shouldClose=true) 트랜잭션 커밋 후 즉시 QuizCoreService.closeQuestion을 호출한다")
  void submitAnswer_allAnswered_triggersCloseQuestion() {
    Long roomId = 42L;
    Long participantId = 900L;
    Long runQuestionId = 812L;
    Long choiceId = 3001L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(runQuestionId, choiceId);
    SubmitAnswerTxResult txResult = new SubmitAnswerTxResult(runQuestionId, 3, true);

    Set<Long> onlineParticipantIds = Set.of(participantId, 901L, 902L);
    given(answerTxService.submitAnswer(roomId, participantId, request, onlineParticipantIds)).willReturn(txResult);

    AnswerProgressResponse response = answerService.submitAnswer(roomId, participantId, request, onlineParticipantIds);

    assertThat(response.answeredCount()).isEqualTo(3);
    verify(quizCoreService).closeQuestion(roomId, runQuestionId);
  }

  @Test
  @DisplayName("countAnswered는 AnswerTxService.countAnswered를 호출한다")
  void countAnswered_delegatesToTxService() {
    Long runQuestionId = 812L;
    given(answerTxService.countAnswered(runQuestionId)).willReturn(7L);

    long count = answerService.countAnswered(runQuestionId);

    assertThat(count).isEqualTo(7L);
    verify(answerTxService).countAnswered(runQuestionId);
  }
}
