package com.sahmhoot.quiz.dto.message;

import java.time.Instant;

/**
 * 06 실시간 규격 ANSWER_PROGRESS 메시지.
 * 진행 중 소유자(교수) 큐(/user/queue/rooms/{roomId}/stats)로 응답 인원 현황을 전송한다.
 * 전송 주기: 최대 초당 2회 (06 규격 97행).
 */
public record AnswerProgressMessage(
    String type,
    Instant sentAt,
    Long runQuestionId,
    int answeredCount
) {

  public static AnswerProgressMessage of(Long runQuestionId, int answeredCount, Instant sentAt) {
    return new AnswerProgressMessage("ANSWER_PROGRESS", sentAt, runQuestionId, answeredCount);
  }
}
