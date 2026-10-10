package com.sahmhoot.quiz.controller;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.service.AnswerService;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 5. Answer: STOMP 답변 제출 메시지 핸들러.
 * 06 실시간 규격 2절 및 3절 기준:
 * - Principal의 이름은 userId이다.
 * - 소유자가 answer를 보내면 FORBIDDEN을 반환한다 (실시간 규격 166행).
 * - userId를 통해 해당 방의 participantId(room_participants.id)를 조회하여 답변을 처리한다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class QuizAnswerMessageController {

  private final AnswerService answerService;
  private final RoomPort roomPort;

  /**
   * 참가자 답변 제출 핸들러.
   */
  @MessageMapping("/rooms/{roomId}/answer")
  public void handleAnswer(
      @DestinationVariable("roomId") Long roomId,
      @Valid @Payload SubmitAnswerRequest request,
      Principal principal
  ) {
    if (principal == null || principal.getName() == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }

    Long userId;
    try {
      userId = Long.parseLong(principal.getName());
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 인증 정보입니다.");
    }
    RoomSnapshot room = roomPort.getRoom(roomId);

    // 06 실시간 규격 166행: 소유자의 answer 전송은 FORBIDDEN
    if (room.hostId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "방 소유자는 답변을 제출할 수 없습니다.");
    }

    // userId -> participantId 조회 (없으면 NOT_PARTICIPANT)
    Long participantId = roomPort.findParticipantId(roomId, userId);
    // TODO: Room 담당의 접속 상태 기능과 연동해 실제 접속 중인 비소유 participantId 집합을 전달한다.
    // 연동 전에는 조기 마감을 비활성화한다.
    AnswerProgressResponse progress = answerService.submitAnswer(roomId, participantId, request, Set.of());
    log.debug("Handled answer for roomId={}, participantId={}, answeredCount={}",
        roomId, participantId, progress.answeredCount());
  }
}
