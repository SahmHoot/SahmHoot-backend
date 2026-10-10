package com.sahmhoot.quiz.dto.message;

import java.time.Instant;

/**
 * 06 실시간 규격 ROOM_WAITING 메시지 규격 (결과 닫기 후 대기로 복귀 시 전송).
 */
public record RoomWaitingMessage(
    String type,
    Instant sentAt
) {

  public static RoomWaitingMessage of(Instant sentAt) {
    return new RoomWaitingMessage("ROOM_WAITING", sentAt);
  }
}
