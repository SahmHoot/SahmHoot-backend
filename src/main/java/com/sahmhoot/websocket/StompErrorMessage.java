package com.sahmhoot.websocket;

import java.time.Instant;

/** 06 ERROR 메시지. /user/queue/errors로 요청 보낸 탭에만 간다. */
public record StompErrorMessage(String type, Instant sentAt, String code, String message) {

  public static StompErrorMessage of(String code, String message) {
    return new StompErrorMessage("ERROR", Instant.now(), code, message);
  }
}
