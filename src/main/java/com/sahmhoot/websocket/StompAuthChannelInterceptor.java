package com.sahmhoot.websocket;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 클라이언트가 보낸 STOMP 프레임을 검사한다. 허용 목록에 있는 구독·전송 경로만 통과시키고 나머지는 거부한다(default deny).
 *
 * <p>{@code @EnableWebSocketSecurity}는 쓰지 않는다(06 3절). 방 권한 검사는 아래 TODO 자리에서 각 담당이 구현한다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private static final List<Pattern> SUBSCRIBE_ALLOWED =
      List.of(
          Pattern.compile("/topic/rooms/\\d+/(quiz|chat|reaction|presence)"),
          Pattern.compile("/user/queue/rooms/\\d+/(stats|me)"),
          Pattern.compile("/user/queue/errors"));

  /** 전송은 /app 경로만 허용한다. /topic·/queue·/user로 직접 보내는 프레임은 여기에 걸리지 않아 모두 거부된다. */
  private static final List<Pattern> SEND_ALLOWED =
      List.of(Pattern.compile("/app/rooms/\\d+/(answer|chat|reaction)"));

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      accessor = StompHeaderAccessor.wrap(message);
    }
    StompCommand command = accessor.getCommand();
    if (command == null) {
      return message;
    }

    switch (command) {
      case CONNECT -> {
        // TODO(3): CONNECT 헤더의 Bearer JWT 검증, Principal(name = userId) 설정, 실패 시 거부
      }
      case SUBSCRIBE -> {
        String destination = accessor.getDestination();
        if (!isSubscribeAllowed(destination)) {
          throw new AccessDeniedException("구독할 수 없는 경로입니다: " + destination);
        }
        // TODO(2): SUBSCRIBE 시 그 방 참여자인지, stats는 소유자인지 확인
      }
      case SEND -> {
        String destination = accessor.getDestination();
        if (!isSendAllowed(destination)) {
          throw new AccessDeniedException("보낼 수 없는 경로입니다: " + destination);
        }
        // TODO(1,5): SEND 시 비소유 참여자·채팅 제한 여부 확인
      }
      default -> {}
    }
    return message;
  }

  static boolean isSubscribeAllowed(String destination) {
    return matchesAny(SUBSCRIBE_ALLOWED, destination);
  }

  static boolean isSendAllowed(String destination) {
    return matchesAny(SEND_ALLOWED, destination);
  }

  private static boolean matchesAny(List<Pattern> patterns, String destination) {
    if (destination == null) {
      return false;
    }
    return patterns.stream().anyMatch(p -> p.matcher(destination).matches());
  }
}
