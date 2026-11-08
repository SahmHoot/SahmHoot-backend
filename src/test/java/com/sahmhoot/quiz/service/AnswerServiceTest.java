package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class AnswerServiceTest {

  private RoomLockManager roomLockManager;

  @Mock
  private AnswerTxService answerTxService;

  @Mock
  private QuizCoreService quizCoreService;

  @Mock private RoomPort rooms;
  @Mock private ParticipantPresencePort presence;
  @Mock private AnswerProgressPublisher progress;

  private AnswerService answerService;

  @BeforeEach
  void setUp() {
    roomLockManager = new RoomLockManager();
    answerService = new AnswerService(
        roomLockManager,
        answerTxService,
        quizCoreService, rooms, presence, progress
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
    givenRoomAndPresence(onlineParticipantIds);
    given(answerTxService.submitAnswer(roomId, participantId, request, onlineParticipantIds)).willReturn(txResult);

    AnswerProgressResponse response = answerService.submitAnswer(roomId, participantId, request);

    assertThat(response.runQuestionId()).isEqualTo(runQuestionId);
    assertThat(response.answeredCount()).isEqualTo(2);
    verify(answerTxService).submitAnswer(roomId, participantId, request, onlineParticipantIds);
    verify(progress).publish(roomId, 1L, response);
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
    givenRoomAndPresence(onlineParticipantIds);
    given(answerTxService.submitAnswer(roomId, participantId, request, onlineParticipantIds)).willReturn(txResult);

    AnswerProgressResponse response = answerService.submitAnswer(roomId, participantId, request);

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

  @Test
  void failedSaveDoesNotPublishOrClose() {
    givenRoomAndPresence(Set.of(900L));
    var request = new SubmitAnswerRequest(812L, 3001L);
    given(answerTxService.submitAnswer(42L, 900L, request, Set.of(900L)))
        .willThrow(new IllegalStateException("test save failure"));

    assertThatThrownBy(() -> answerService.submitAnswer(42L, 900L, request)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(progress, quizCoreService);
    assertThat(roomLockManager.isLocked(42L)).isFalse();
  }

  @Test
  void disconnectRechecksLatestOnlineParticipants() {
    givenRoomAndPresence(Set.of(900L, 901L));
    given(answerTxService.findFullyAnsweredQuestion(42L, Set.of(900L, 901L))).willReturn(Optional.of(812L));

    answerService.closeIfAllAnswered(42L);

    verify(quizCoreService).closeQuestion(42L, 812L);
    verifyNoInteractions(progress);
  }

  @Test
  void rejectsAmbientTransactionEvenWhenRoomLockIsAlreadyHeld() {
    roomLockManager.executeWithLock(42L, () -> {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatThrownBy(() -> answerService.submitAnswer(42L, 900L, new SubmitAnswerRequest(812L, 3001L)))
            .isInstanceOf(IllegalStateException.class);
      } finally {
        TransactionSynchronizationManager.setActualTransactionActive(false);
      }
    });
    verifyNoInteractions(rooms, presence, answerTxService, progress, quizCoreService);
  }

  @Test
  void lateDisconnectInClosedRoomDoesNothing() {
    given(rooms.findRoom(42L)).willReturn(Optional.of(new RoomSnapshot(42L, 1L, RoomStatus.CLOSED)));
    answerService.closeIfAllAnswered(42L);
    verifyNoInteractions(presence, answerTxService, progress, quizCoreService);
  }

  private void givenRoomAndPresence(Set<Long> online) {
    given(rooms.findRoom(42L)).willReturn(Optional.of(new RoomSnapshot(42L, 1L, RoomStatus.PLAYING)));
    given(presence.getOnlineParticipantIds(42L)).willAnswer(call -> {
      assertThat(roomLockManager.isLocked(42L)).isTrue();
      return online;
    });
  }
}
