package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

  /**
   * 참가자의 답변을 제출/수정하고, 전원 응답 시 문항을 즉시 마감한다.
   * 방 단위 잠금(RoomLockManager) 안에서 실행된다.
   */
  public AnswerProgressResponse submitAnswer(
      Long roomId,
      Long participantId,
      SubmitAnswerRequest request,
      Set<Long> onlineParticipantIds
  ) {
    return roomLockManager.executeWithLock(roomId, () -> {
      SubmitAnswerTxResult result = answerTxService.submitAnswer(roomId, participantId, request, onlineParticipantIds);

      // 전원 응답 판정 만족 시, 답변 트랜잭션 커밋 완료 후 1번 문항 마감 호출
      if (result.shouldClose()) {
        log.info("All online participants answered. Triggering immediate close for runQuestionId={}",
            result.runQuestionId());
        quizCoreService.closeQuestion(roomId, result.runQuestionId());
      }

      // TODO: 소유자에게 실시간 ANSWER_PROGRESS 메시지 발행 (최대 초당 2회 제한)
      return new AnswerProgressResponse(result.runQuestionId(), result.answeredCount());
    });
  }

  /**
   * 특정 문항의 응답 인원 수를 조회한다.
   */
  public long countAnswered(Long runQuestionId) {
    return answerTxService.countAnswered(runQuestionId);
  }
}
