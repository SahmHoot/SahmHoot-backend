package com.sahmhoot.websocket.presence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link RoomPresencePort} 구현. 조회를 {@link RoomPresenceTracker}에 위임한다.
 *
 * <p>추적기와 다른 빈으로 둔다. 방 모듈 테스트가 {@code @MockitoBean RoomPresencePort}로 포트를 바꾸면 이 빈만
 * 교체되고, 추적기와 인터셉터는 그대로 주입된다.
 */
@Component
@RequiredArgsConstructor
public class RoomPresenceAdapter implements RoomPresencePort {

  private final RoomPresenceTracker tracker;

  @Override
  public RoomPresenceSnapshot getPresence(Long roomId) {
    return tracker.snapshot(roomId);
  }
}
