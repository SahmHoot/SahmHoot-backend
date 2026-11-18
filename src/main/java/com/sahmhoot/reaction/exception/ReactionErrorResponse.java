package com.sahmhoot.reaction.exception;

import java.time.Instant;

/** /user/queue/errors로 보내는 ERROR 메시지. 퀴즈 답안 오류와 같은 형태다. */
public record ReactionErrorResponse(String type, Instant sentAt, String code, String message) {
  public static ReactionErrorResponse of(ReactionErrorCode code, Instant sentAt) {
    return new ReactionErrorResponse("ERROR", sentAt, code.name(), code.message());
  }
}
