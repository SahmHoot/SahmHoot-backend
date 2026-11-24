package com.sahmhoot.quiz.dto.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahmhoot.questionimage.dto.RunQuestionImage;
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
    List<RunChoiceDetail> choices,
    @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = RunQuestionImage.DisabledFilter.class) RunQuestionImage image
) implements QuizMessage {

  public QuestionOpenedMessage {
    choices = List.copyOf(choices);
  }

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
      Instant sentAt,
      RunQuestionImage image
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
        choices,
        image
    );
  }
}
