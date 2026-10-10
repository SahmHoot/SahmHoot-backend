package com.sahmhoot.quiz.dto.message;

import java.time.Instant;

/**
 * 06 실시간 규격 QUIZ_CANCELED 메시지 규격 (퀴즈 중단 시 전송).
 */
public record QuizCanceledMessage(
    String type,
    Instant sentAt,
    Long runId
) {

  public static QuizCanceledMessage of(Long runId, Instant sentAt) {
    return new QuizCanceledMessage("QUIZ_CANCELED", sentAt, runId);
  }
}
