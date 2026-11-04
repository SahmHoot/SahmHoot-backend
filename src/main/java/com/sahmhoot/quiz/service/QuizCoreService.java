package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 1. Quiz Core: 퀴즈 시작 / 문제 진행 / 타이머 / 종료 서비스 코디네이터.
 * 방 단위 잠금(RoomLockManager)을 획득한 후 별도의 스프링 빈인 QuizCoreTxService를 호출하여
 * "잠금 획득 -> 트랜잭션 서비스 호출(커밋) -> 잠금 해제" 아키텍처를 준수한다.
 *
 * <p>자동 진행 예약(#23)은 트랜잭션 서비스 호출이 끝난 뒤(커밋 완료), 잠금을 놓기 전에 등록한다.
 * 트랜잭션 도중에 예약하면 롤백된 상태의 예약이 남거나, 커밋 전에 실행되어 DB에 없는 문항을 볼 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizCoreService {

  private final RoomLockManager roomLockManager;
  private final QuizCoreTxService quizCoreTxService;
  private final QuizTaskScheduler taskScheduler;

  /**
   * 05 API 명세 23번 퀴즈 시작 (POST /api/rooms/{roomId}/quiz-runs).
   */
  public StartQuizResponse startQuiz(Long roomId, StartQuizRequest request, Long hostUserId) {
    return roomLockManager.executeWithLock(roomId, () -> {
      StartQuizResponse response = quizCoreTxService.startQuiz(roomId, request, hostUserId);
      // 커밋 완료. 1번 문항 마감을 예약한다.
      scheduleClose(roomId, response.firstRunQuestionId(), response.closesAt());
      return response;
    });
  }

  /**
   * 05 API 명세 24번 퀴즈 중단 (DELETE /api/rooms/{roomId}/quiz-runs/{runId}).
   */
  public void abortQuiz(Long roomId, Long runId, Long hostUserId) {
    roomLockManager.executeWithLock(roomId, () -> {
      quizCoreTxService.abortQuiz(roomId, runId, hostUserId);
      // 커밋 완료. 그 방의 남은 마감·다음 문항·결과 예약을 모두 취소한다.
      // 커밋 뒤에 취소해야 중단 직전에 새로 걸린 예약까지 지울 수 있다. 중단이 예외로 실패하면 여기까지 오지 않는다.
      taskScheduler.cancelAll(roomId);
    });
  }

  /**
   * 05 API 명세 25번 결과 닫기 / 대기로 (POST /api/rooms/{roomId}/wait).
   */
  public void closeResult(Long roomId, Long hostUserId) {
    roomLockManager.executeWithLock(roomId, () -> quizCoreTxService.closeResult(roomId, hostUserId));
  }

  /**
   * 문항 마감 처리 (마감 예약 실행 또는 5번 AnswerService의 전원 응답 판정 시 호출).
   * QUESTION_CLOSED는 트랜잭션 커밋 후 발행한다. 실제로 닫았을 때만 nextOpensAt(마감을 처리한 시각 + 3초)에
   * 다음 문항 열기 또는 결과 처리를 예약한다. 이미 닫혔으면 아무것도 예약하지 않으므로, 조기 마감 뒤에
   * 원래 마감 예약이 실행되어도 다음 진행이 두 번 걸리지 않는다.
   */
  public void closeQuestion(Long roomId, Long runQuestionId) {
    roomLockManager.executeWithLock(roomId, () -> {
      CloseQuestionResult result = quizCoreTxService.closeQuestion(roomId, runQuestionId);
      if (!result.processed()) {
        return;
      }

      // 커밋 완료. 예약된 일은 아래 public 메서드를 다시 거치므로 실행 때 잠금·DB 재확인을 한다.
      if (result.isLast()) {
        taskScheduler.schedule(roomId, result.nextOpensAt(), () -> finishQuiz(roomId, result.runId()));
      } else {
        // 다음 문항이 열리면 openQuestion이 그 문항의 마감을 다시 예약해 진행이 이어진다.
        taskScheduler.schedule(roomId, result.nextOpensAt(),
            () -> openQuestion(roomId, result.runId(), result.nextOrderNo()));
      }
    });
  }

  /**
   * 퀴즈 회차 종료 및 결과 집계.
   * 마지막 문항 마감 3초 뒤 예약으로 호출된다.
   */
  public QuizResultResponse finishQuiz(Long roomId, Long runId) {
    return roomLockManager.executeWithLock(roomId, () -> {
      QuizResultResponse result = quizCoreTxService.finishQuiz(roomId, runId);
      if (result != null) {
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
      OpenQuestionResult result = quizCoreTxService.openQuestion(roomId, runId, orderNo);
      // 이미 열렸거나 중단된 회차라 아무것도 하지 않았으면 예약도 걸지 않는다.
      if (!result.opened()) {
        return;
      }
      // 커밋 완료. 방금 연 문항의 마감을 예약한다.
      scheduleClose(roomId, result.runQuestionId(), result.closesAt());
    });
  }

  /**
   * 문항 마감 예약. 06 규격 5절에 따라 closes_at + 1초까지 답을 받으므로 그 시각에 닫는다.
   * 예약이 실행될 때 이미 닫혔거나(전원 응답 조기 마감) 회차가 중단됐으면
   * closeQuestion이 DB 상태를 보고 아무것도 하지 않는다.
   */
  private void scheduleClose(Long roomId, Long runQuestionId, Instant closesAt) {
    taskScheduler.schedule(roomId, closesAt.plusSeconds(1), () -> closeQuestion(roomId, runQuestionId));
  }
}
