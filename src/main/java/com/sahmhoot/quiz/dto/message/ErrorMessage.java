package com.sahmhoot.quiz.dto.message;

import java.time.Instant;

/**
 * 06 실시간 규격 ERROR 메시지 규격.
 * 실시간 규격 30행: /user/queue/errors 채널로 요청을 보낸 탭(세션)에만 전송된다.
 * 페이로드 형태: { "type": "ERROR", "sentAt": "...", "code": "...", "message": "..." }
 */
public record ErrorMessage(
    String type,
    Instant sentAt,
    String code,
    String message
) {

  public static ErrorMessage of(String code, String message, Instant sentAt) {
    return new ErrorMessage("ERROR", sentAt, code, message);
  }
}
