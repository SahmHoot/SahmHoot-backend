package com.sahmhoot.room.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sahmhoot.room.entity.RoomStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 05 API 명세 #21. 정답·집계의 노출 범위는 RoomStateService에서 제한한다. */
public record RoomStateResponse(
    Instant serverTime, RoomDetail room, QuizRun quizRun, Me me, int onlineCount,
    Quiz quiz, Message pinnedMessage, List<Message> recentMessages,
    @JsonInclude(JsonInclude.Include.NON_NULL) Progress progress,
    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Integer> reactionStats
) {
  public record RoomDetail(Long id, String title, RoomStatus status, String joinCode,
      String joinUrl, String ownerName) {}

  public record Me(Long participantId, String nickname, boolean isOwner, boolean chatBlocked) {}

  public record QuizRun(RunStatus status) {}

  public enum RunStatus { READY, RUNNING, FINISHED, ABORTED }

  public enum Phase { QUESTION, FEEDBACK, RESULT }

  public record Quiz(Long runId, String questionSetTitle, int totalQuestions, Phase phase,
      CurrentQuestion currentQuestion, Instant nextOpensAt,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<MyAnswer> myAnswers, Result result) {}

  public record CurrentQuestion(Long runQuestionId, int questionNo, String questionType,
      int timeLimitSeconds, String content, List<Choice> choices, Instant openedAt,
      Instant closesAt, Instant closedAt, Long correctChoiceId) {}

  public record Choice(Long id, int orderNo, String content) {}

  public record MyAnswer(Long runQuestionId, Long choiceId) {}

  public record Progress(Long runQuestionId, int answeredCount) {}

  /** QUIZ_FINISHED.result와 같은 필드. 다른 모듈의 DTO를 직접 참조하지 않는다. */
  public record Result(List<QuestionResult> questions) {
    public Result { questions = List.copyOf(questions); }
  }

  public record QuestionResult(Long runQuestionId, int questionNo, String content,
      Long correctChoiceId, int answeredCount, int correctCount, int incorrectCount,
      List<ChoiceResult> choices) {
    public QuestionResult { choices = List.copyOf(choices); }
  }

  public record ChoiceResult(Long id, int orderNo, String content, int count) {}

  public record Message(Long id, Long senderParticipantId, String nickname, boolean isOwner,
      String content, Long parentId, String parentPreview, Instant createdAt) {}
}
