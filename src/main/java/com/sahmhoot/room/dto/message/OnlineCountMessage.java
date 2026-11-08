package com.sahmhoot.room.dto.message;

import java.time.Instant;

/** 06 규격: /topic/rooms/{roomId}/presence에 증감분이 아닌 전체 인원을 전송한다. */
public record OnlineCountMessage(String type, Instant sentAt, int onlineCount) {
  public static OnlineCountMessage of(Instant sentAt, int count) {
    return new OnlineCountMessage("ONLINE_COUNT", sentAt, count);
  }
}
