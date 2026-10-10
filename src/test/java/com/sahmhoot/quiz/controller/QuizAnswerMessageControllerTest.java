package com.sahmhoot.quiz.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.service.AnswerService;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.security.Principal;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizAnswerMessageControllerTest {

  @Mock
  private AnswerService answerService;

  @Mock
  private RoomPort roomPort;

  @InjectMocks
  private QuizAnswerMessageController messageController;

  @Test
  @DisplayName("Principal이 없으면 UNAUTHORIZED 예외를 던진다")
  void handleAnswer_nullPrincipal_throwsUnauthorized() {
    Long roomId = 42L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(812L, 3001L);

    assertThatThrownBy(() -> messageController.handleAnswer(roomId, request, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  @DisplayName("Principal의 이름이 숫자가 아니면 UNAUTHORIZED 예외를 던진다")
  void handleAnswer_nonNumericPrincipal_throwsUnauthorized() {
    Long roomId = 42L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(812L, 3001L);
    Principal principal = () -> "invalid-user-id";

    assertThatThrownBy(() -> messageController.handleAnswer(roomId, request, principal))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.UNAUTHORIZED);
  }

  @Test
  @DisplayName("방 소유자(교수)가 답변을 제출하면 FORBIDDEN 예외를 던진다 (Issue 6)")
  void handleAnswer_roomOwner_throwsForbidden() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(812L, 3001L);
    Principal principal = () -> String.valueOf(hostUserId);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.PLAYING));

    assertThatThrownBy(() -> messageController.handleAnswer(roomId, request, principal))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  @DisplayName("학생 참가자(userId)의 participantId를 정상 조회하여 AnswerService로 위임한다 (Issue 6)")
  void handleAnswer_validParticipant_delegatesToService() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long studentUserId = 200L;
    Long participantId = 900L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(812L, 3001L);
    Principal principal = () -> String.valueOf(studentUserId);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.PLAYING));
    given(roomPort.findParticipantId(roomId, studentUserId)).willReturn(participantId);
    given(answerService.submitAnswer(eq(roomId), eq(participantId), eq(request), eq(Set.of())))
        .willReturn(new AnswerProgressResponse(812L, 1));

    messageController.handleAnswer(roomId, request, principal);

    verify(answerService).submitAnswer(eq(roomId), eq(participantId), eq(request), eq(Set.of()));
  }

  @Test
  @DisplayName("방에 참여하지 않은 사용자가 답변을 제출하면 NOT_PARTICIPANT 예외를 던진다")
  void handleAnswer_notParticipant_throwsNotParticipant() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long strangerUserId = 300L;
    SubmitAnswerRequest request = new SubmitAnswerRequest(812L, 3001L);
    Principal principal = () -> String.valueOf(strangerUserId);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.PLAYING));
    given(roomPort.findParticipantId(roomId, strangerUserId))
        .willThrow(new BusinessException(ErrorCode.NOT_PARTICIPANT));

    assertThatThrownBy(() -> messageController.handleAnswer(roomId, request, principal))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.NOT_PARTICIPANT);
  }
}
