package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 1. Quiz Core: 퀴즈 시작 / 문제 진행 / 타이머 / 종료 서비스 코디네이터.
 * 방 단위 잠금(RoomLockManager)을 획득한 후 별도의 스프링 빈인 QuizCoreTxService를 호출하여
 * "잠금 획득 -> 트랜잭션 서비스 호출(커밋) -> 잠금 해제" 아키텍처를 준수한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizCoreService {

  private final RoomLockManager roomLockManager;
  private final QuizCoreTxService quizCoreTxService;

  /**
   * 05 API 명세 23번 퀴즈 시작 (POST /api/rooms/{roomId}/quiz-runs).
   */
  public StartQuizResponse startQuiz(Long roomId, StartQuizRequest request, Long hostUserId) {
    return roomLockManager.executeWithLock(roomId, () -> {
      StartQuizResponse response = quizCoreTxService.startQuiz(roomId, request, hostUserId);
      // TODO: 실시간 QUESTION_OPENED 메시지 발행 (@TransactionalEventListener 또는 잠금 내 사후 처리)
      // TODO: 1번 문항 마감 예약 등록 (Scheduler)
      return response;
    });
  }

  /**
   * 05 API 명세 24번 퀴즈 중단 (DELETE /api/rooms/{roomId}/quiz-runs/{runId}).
   */
  public void abortQuiz(Long roomId, Long runId, Long hostUserId) {
    roomLockManager.executeWithLock(roomId, () -> {
      quizCoreTxService.abortQuiz(roomId, runId, hostUserId);
      // TODO: 실시간 QUIZ_CANCELED 메시지 발행
      // TODO: 해당 방의 스케줄러 예약 작업 취소
    });
  }

  /**
   * 05 API 명세 25번 결과 닫기 / 대기로 (POST /api/rooms/{roomId}/wait).
   */
  public void closeResult(Long roomId, Long hostUserId) {
    roomLockManager.executeWithLock(roomId, () -> {
      quizCoreTxService.closeResult(roomId, hostUserId);
      // TODO: 실시간 ROOM_WAITING 메시지 발행
    });
  }

  /**
   * 문항 마감 처리 (시간 만료 또는 5번 AnswerService의 전원 응답 판정 시 호출).
   * 실시간 규격 5절: 마감 시 QUESTION_CLOSED 발행 후 3초 뒤 다음 문항 열기(마지막이면 결과 화면)를 예약한다.
   */
  public void closeQuestion(Long roomId, Long runQuestionId) {
    roomLockManager.executeWithLock(roomId, () -> {
      CloseQuestionResult result = quizCoreTxService.closeQuestion(roomId, runQuestionId);
      if (!result.processed()) {
        return;
      }

      // TODO: 실시간 QUESTION_CLOSED 메시지 발행 (correctChoiceId, isLast, nextOpensAt)
      // TODO: 3초 뒤 마지막 문항이면 finishQuiz, 아니면 다음 문항 openQuestion 예약
    });
  }

  /**
   * 퀴즈 회차 종료 및 결과 집계.
   * 마지막 문항 마감 3초 뒤 스케줄러에 의해 호출된다.
   */
  public QuizResultResponse finishQuiz(Long roomId, Long runId) {
    return roomLockManager.executeWithLock(roomId, () -> {
      QuizResultResponse result = quizCoreTxService.finishQuiz(roomId, runId);
      if (result != null) {
        // TODO: 실시간 QUIZ_FINISHED 메시지 발행
        log.info("Finished quiz and aggregated result for runId={}", runId);
      }
      return result;
    });
  }

  /**
   * 다음 문항 열기.
   */
  public void openQuestion(Long roomId, Long runId, int orderNo) {
    roomLockManager.executeWithLock(roomId, () -> {
      quizCoreTxService.openQuestion(roomId, runId, orderNo);
      // TODO: 실시간 QUESTION_OPENED 메시지 발행
      // TODO: 문항 마감 예약 등록 (closesAt + 1s)
    });
  }
}
