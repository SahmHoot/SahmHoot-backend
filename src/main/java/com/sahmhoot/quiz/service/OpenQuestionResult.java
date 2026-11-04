package com.sahmhoot.quiz.service;

import java.time.Instant;

/**
 * 문항 열기 트랜잭션 실행 결과 레코드.
 * 실제로 열었을 때만 마감 예약(closesAt + 1초)에 필요한 문항 ID와 마감 시각을 전달한다(#23).
 * 이미 열렸거나 중단된 회차처럼 아무것도 하지 않은 호출은 {@link #ignored()}로 돌려준다.
 */
public record OpenQuestionResult(
    boolean opened,
    Long runQuestionId,
    Instant closesAt
) {

  public static OpenQuestionResult ignored() {
    return new OpenQuestionResult(false, null, null);
  }

  public static OpenQuestionResult opened(Long runQuestionId, Instant closesAt) {
    return new OpenQuestionResult(true, runQuestionId, closesAt);
  }
}
