package com.sahmhoot.websocket.presence;

import java.util.Objects;
import java.util.Set;

/** 사용자 단위 접속 스냅샷. 소유자는 집합에 포함되지만 학생 접속 인원에서는 제외한다. */
public record RoomPresenceSnapshot(Long roomId, Set<Long> onlineUserIds, boolean ownerOnline) {

  public RoomPresenceSnapshot {
    Objects.requireNonNull(roomId, "roomId");
    onlineUserIds = Set.copyOf(onlineUserIds);
    if (ownerOnline && onlineUserIds.isEmpty()) {
      throw new IllegalArgumentException("An online owner must be included in onlineUserIds");
    }
  }
}
