package com.sahmhoot.quiz.dto.message;

import com.sahmhoot.quiz.entity.QuestionType;
import java.time.Instant;
import java.util.List;

/**
 * 06 실시간 규격 QUESTION_OPENED 메시지 규격.
 */
public record QuestionOpenedMessage(
    String type,
    Instant sentAt,
    Long runId,
    Long runQuestionId,
    int questionNo,
    int totalQuestions,
    QuestionType questionType,
    String content,
    int timeLimitSeconds,
    Instant closesAt,
    List<RunChoiceDetail> choices
) {

  public static QuestionOpenedMessage of(
      Long runId,
      Long runQuestionId,
      int questionNo,
      int totalQuestions,
      QuestionType questionType,
      String content,
      int timeLimitSeconds,
      Instant closesAt,
      List<RunChoiceDetail> choices,
      Instant sentAt
  ) {
    return new QuestionOpenedMessage(
        "QUESTION_OPENED",
        sentAt,
        runId,
        runQuestionId,
        questionNo,
        totalQuestions,
        questionType,
        content,
        timeLimitSeconds,
        closesAt,
        choices
    );
  }
}
