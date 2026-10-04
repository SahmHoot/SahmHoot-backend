package com.sahmhoot.quiz.dto.message;

import com.sahmhoot.quiz.dto.QuizResultResponse;
import java.time.Instant;

/**
 * 06 실시간 규격 QUIZ_FINISHED 메시지 규격.
 */
public record QuizFinishedMessage(
    String type,
    Instant sentAt,
    Long runId,
    QuizResultResponse result
) {

  public static QuizFinishedMessage of(Long runId, QuizResultResponse result, Instant sentAt) {
    return new QuizFinishedMessage("QUIZ_FINISHED", sentAt, runId, result);
  }
}
