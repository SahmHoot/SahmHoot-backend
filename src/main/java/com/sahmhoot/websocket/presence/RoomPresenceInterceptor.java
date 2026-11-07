package com.sahmhoot.websocket.presence;

import com.sahmhoot.websocket.RoomAccessChecker;
import com.sahmhoot.websocket.StompPrincipal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * 권한 검사를 통과한 SUBSCRIBE·UNSUBSCRIBE를 {@link RoomPresenceTracker}에 전달한다.
 *
 * <p>WebSocketConfig에서 {@code StompAuthChannelInterceptor} 뒤에 등록한다. 앞 인터셉터가 프레임을 거부(null)하면
 * 이 인터셉터는 호출되지 않으므로, 여기까지 온 방 채널 구독은 권한 검사를 통과한 것이다. 구독과 해제를 같은
 * 수신 순서로 처리하므로 빠르게 구독했다 해제해도 순서가 바뀌지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomPresenceInterceptor implements ChannelInterceptor {

  /** 방 채널: /topic/rooms/{roomId}/..., /user/queue/rooms/{roomId}/... 허용 목록은 권한 인터셉터가 검사한다. */
  private static final Pattern ROOM_CHANNEL =
      Pattern.compile("^/(?:topic|user/queue)/rooms/(\\d{1,18})/");

  private final RoomPresenceTracker tracker;
  private final RoomAccessChecker roomAccess;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    try {
      switch (accessor.getCommand()) {
        case SUBSCRIBE -> subscribe(accessor);
        case UNSUBSCRIBE ->
            tracker.unsubscribed(accessor.getSessionId(), accessor.getSubscriptionId());
        default -> {}
      }
    } catch (RuntimeException e) {
      // 접속 집계에 실패해도 구독 자체는 막지 않는다.
      log.error(
          "Room presence tracking failed: command={}, sessionId={}",
          accessor.getCommand(),
          accessor.getSessionId(),
          e);
    }
    return message;
  }

  private void subscribe(StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();
    Matcher m = destination == null ? null : ROOM_CHANNEL.matcher(destination);
    if (m == null
        || !m.lookingAt()
        || !(accessor.getUser() instanceof StompPrincipal principal)) {
      return;
    }
    long roomId = Long.parseLong(m.group(1));
    long userId = principal.user().userId();
    // 소유자 조회(SQL)는 추적기 잠금 밖에서 한다. 그 사이 끊긴 세션은 추적기가 잠금 안에서 다시 걸러 낸다.
    boolean owner = roomAccess.isOwner(roomId, userId);
    tracker.subscribed(
        accessor.getSessionId(),
        accessor.getSessionAttributes(),
        accessor.getSubscriptionId(),
        userId,
        roomId,
        owner);
  }
}
