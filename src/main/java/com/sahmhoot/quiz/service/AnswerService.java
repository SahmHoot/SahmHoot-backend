package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 5. Answer + Result: 답 제출 및 전원 응답 판정 서비스 코디네이터.
 * 방 단위 잠금(RoomLockManager) 안에서 별도 스프링 빈인 AnswerTxService를 호출하여 커밋한 후,
 * 전원 응답 시 1번 QuizCoreService.closeQuestion을 연동 호출한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerService {

  private final RoomLockManager roomLockManager;
  private final AnswerTxService answerTxService;
  private final QuizCoreService quizCoreService;
  private final RoomPort rooms;
  private final ParticipantPresencePort presence;
  private final AnswerProgressPublisher progressPublisher;

  /**
   * 참가자의 답변을 제출/수정하고, 전원 응답 시 문항을 즉시 마감한다.
   * 방 단위 잠금(RoomLockManager) 안에서 실행된다.
   */
  public AnswerProgressResponse submitAnswer(
      Long roomId,
      Long participantId,
      SubmitAnswerRequest request
  ) {
    requireNoTransaction();
    return roomLockManager.executeWithLock(roomId, () -> {
      var room = rooms.findRoom(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
      if (room.status() == RoomStatus.CLOSED) throw new BusinessException(ErrorCode.ROOM_CLOSED);
      var onlineParticipantIds = presence.getOnlineParticipantIds(roomId);
      SubmitAnswerTxResult result = answerTxService.submitAnswer(roomId, participantId, request, onlineParticipantIds);
      var response = new AnswerProgressResponse(result.runQuestionId(), result.answeredCount());
      progressPublisher.publish(roomId, room.hostId(), response);

      // 전원 응답 판정 만족 시, 답변 트랜잭션 커밋 완료 후 1번 문항 마감 호출
      if (result.shouldClose()) {
        log.info("All online participants answered. Triggering immediate close for runQuestionId={}",
            result.runQuestionId());
        quizCoreService.closeQuestion(roomId, result.runQuestionId());
      }

      return response;
    });
  }

  public void closeIfAllAnswered(Long roomId) {
    requireNoTransaction();
    roomLockManager.executeWithLock(roomId, () -> {
      var room = rooms.findRoom(roomId);
      if (room.isEmpty() || room.get().status() != RoomStatus.PLAYING) return;
      var onlineParticipantIds = presence.getOnlineParticipantIds(roomId);
      answerTxService.findFullyAnsweredQuestion(roomId, onlineParticipantIds)
          .ifPresent(questionId -> quizCoreService.closeQuestion(roomId, questionId));
    });
  }

  private void requireNoTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("AnswerService must be called before starting a transaction");
    }
  }

  /**
   * 특정 문항의 응답 인원 수를 조회한다.
   */
  public long countAnswered(Long runQuestionId) {
    return answerTxService.countAnswered(runQuestionId);
  }
}
