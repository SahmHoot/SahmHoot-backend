package com.sahmhoot.quiz.dto.message;

import java.time.Instant;

/**
 * 06 실시간 규격 QUESTION_CLOSED 메시지 규격.
 */
public record QuestionClosedMessage(
    String type,
    Instant sentAt,
    Long runId,
    Long runQuestionId,
    Long correctChoiceId,
    boolean isLast,
    Instant nextOpensAt
) {

  public static QuestionClosedMessage of(
      Long runId,
      Long runQuestionId,
      Long correctChoiceId,
      boolean isLast,
      Instant nextOpensAt,
      Instant sentAt
  ) {
    return new QuestionClosedMessage(
        "QUESTION_CLOSED",
        sentAt,
        runId,
        runQuestionId,
        correctChoiceId,
        isLast,
        nextOpensAt
    );
  }
}
