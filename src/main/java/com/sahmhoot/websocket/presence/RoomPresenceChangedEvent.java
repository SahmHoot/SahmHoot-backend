package com.sahmhoot.websocket.presence;

import java.time.Instant;
import java.util.Objects;

/**
 * #65가 사용자 집합 또는 소유자 연결 유무가 바뀔 때 발행하는 Spring 애플리케이션 이벤트.
 * 탭 하나만 닫거나 동일한 끊김을 재처리해 사용자 단위 상태가 같으면 발행하지 않는다.
 * STOMP 페이로드가 아니며, 공개 ONLINE_COUNT 발행은 #45가 담당한다.
 * 제공자는 같은 방의 상태 갱신 순서대로 동기 알림을 전달한다. occurredAt은 상태 변경 시각이다.
 *
 * <p>처리기 규칙: 처리기는 제공자의 내부 잠금 안에서 동기로 호출되며, 그동안 모든 방의 구독·끊김 처리가 기다린다.
 * <ul>
 *   <li>방 잠금·DB 조회 없이 빠르게 끝낸다. 무거운 일은 예약 작업으로 넘긴다(#83·#46 방식).
 *   <li>예외를 밖으로 던지지 않는다. 던지면 같은 이벤트의 나머지 처리기가 호출되지 않는다.
 * </ul>
 */
public record RoomPresenceChangedEvent(
    RoomPresenceSnapshot previous,
    RoomPresenceSnapshot current,
    Instant occurredAt
) {

  public RoomPresenceChangedEvent {
    Objects.requireNonNull(previous, "previous");
    Objects.requireNonNull(current, "current");
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (!previous.roomId().equals(current.roomId())) {
      throw new IllegalArgumentException("Presence snapshots must belong to the same room");
    }
    if (previous.equals(current)) {
      throw new IllegalArgumentException("Presence must change before publishing an event");
    }
  }

  public boolean ownerConnectionChanged() {
    return previous.ownerOnline() != current.ownerOnline();
  }
}
