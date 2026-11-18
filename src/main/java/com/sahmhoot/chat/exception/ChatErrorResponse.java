package com.sahmhoot.chat.exception;

import java.time.Instant;

public record ChatErrorResponse(String type, Instant sentAt, String code, String message) {
  public static ChatErrorResponse of(ChatErrorCode code, Instant sentAt) {
    return new ChatErrorResponse("ERROR", sentAt, code.name(), code.message());
  }
}
