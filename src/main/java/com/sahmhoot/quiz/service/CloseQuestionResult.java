package com.sahmhoot.quiz.service;

import java.time.Instant;

/**
 * 문항 마감 트랜잭션 실행 결과 레코드.
 * 실시간 규격 QUESTION_CLOSED 메시지 생성 및 다음 문항/종료 스케줄링에 필요한 정보를 전달한다.
 */
public record CloseQuestionResult(
    boolean processed,
    Long runId,
    Long runQuestionId,
    Long correctChoiceId,
    boolean isLast,
    Integer nextOrderNo,
    Instant nextOpensAt,
    Instant closedAt
) {

  public static CloseQuestionResult ignored() {
    return new CloseQuestionResult(false, null, null, null, false, null, null, null);
  }

  public static CloseQuestionResult closed(
      Long runId,
      Long runQuestionId,
      Long correctChoiceId,
      boolean isLast,
      Integer nextOrderNo,
      Instant nextOpensAt,
      Instant closedAt
  ) {
    return new CloseQuestionResult(
        true,
        runId,
        runQuestionId,
        correctChoiceId,
        isLast,
        nextOrderNo,
        nextOpensAt,
        closedAt
    );
  }
}
