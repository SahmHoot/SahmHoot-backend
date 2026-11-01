package com.sahmhoot.websocket;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.auth.jwt.JwtProvider;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * 06 3절 권한 규칙을 한곳에서 처리한다.
 *
 * <ol>
 *   <li>CONNECT: Authorization: Bearer JWT 검증 → Principal(이름 = userId). 실패하면 거부
 *   <li>SUBSCRIBE·SEND: 허용 목록 → 권한 검사 → 나머지 전부 거부(default deny)
 * </ol>
 *
 * 거부한 프레임은 버리고(null 반환) {@link StompRejectionSender}로 알린다. 예외를 그대로 던지면
 * preserveReceiveOrder 래퍼가 삼켜 클라이언트가 아무것도 받지 못한다.
 *
 * <p>소유자의 answer·reaction 거부, 종료된 방 전송 등 메시지별 규칙은 각 핸들러에서 ERROR로 돌려준다.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

  private static final Pattern ROOM_TOPIC =
      Pattern.compile("^/topic/rooms/(\\d+)/(quiz|chat|reaction|presence)$");
  private static final Pattern ROOM_STATS = Pattern.compile("^/user/queue/rooms/(\\d+)/stats$");
  private static final Pattern ROOM_ME = Pattern.compile("^/user/queue/rooms/(\\d+)/me$");
  private static final String ERRORS = "/user/queue/errors";
  private static final Pattern ROOM_SEND =
      Pattern.compile("^/app/rooms/(\\d+)/(answer|chat|reaction)$");

  private final JwtProvider jwtProvider;
  private final RoomAccessChecker roomAccess;
  private final StompRejectionSender rejection;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || accessor.getCommand() == null) {
      return message;
    }
    StompCommand command = accessor.getCommand();
    try {
      switch (command) {
        case CONNECT -> {
          String token = JwtProvider.stripBearer(accessor.getFirstNativeHeader("Authorization"));
          AuthUser user =
              jwtProvider.parse(token).orElseThrow(() -> deny(StompRejectCode.UNAUTHORIZED));
          accessor.setUser(new StompPrincipal(user));
        }
        case SUBSCRIBE -> checkSubscribe(userOf(accessor), accessor.getDestination());
        case SEND -> checkSend(userOf(accessor), accessor.getDestination());
        default -> {}
      }
    } catch (Rejected e) {
      notifyRejected(accessor, command, e.code);
      return null;
    }
    return message;
  }

  private void notifyRejected(
      StompHeaderAccessor accessor, StompCommand command, StompRejectCode code) {
    String sessionId = accessor.getSessionId();
    if (command == StompCommand.CONNECT) {
      rejection.rejectConnect(sessionId, code.name());
      return;
    }
    Principal p = accessor.getUser();
    if (p != null) {
      rejection.rejectFrame(p.getName(), sessionId, code.name(), code.message());
    }
  }

  private void checkSubscribe(AuthUser user, String dest) {
    if (dest == null) {
      throw deny(StompRejectCode.FORBIDDEN);
    }
    if (ERRORS.equals(dest)) {
      return;
    }
    Matcher m = ROOM_TOPIC.matcher(dest);
    if (m.matches()) {
      requireParticipant(m.group(1), user);
      return;
    }
    m = ROOM_ME.matcher(dest);
    if (m.matches()) {
      requireParticipant(m.group(1), user);
      return;
    }
    m = ROOM_STATS.matcher(dest);
    if (m.matches()) {
      if (!roomAccess.isOwner(Long.parseLong(m.group(1)), user.userId())) {
        throw deny(StompRejectCode.FORBIDDEN);
      }
      return;
    }
    throw deny(StompRejectCode.FORBIDDEN);
  }

  private void checkSend(AuthUser user, String dest) {
    Matcher m = dest == null ? null : ROOM_SEND.matcher(dest);
    if (m == null || !m.matches()) {
      // /topic/**, /queue/**, /user/** 등으로의 SEND는 무조건 거부(서버 알림 흉내 방지).
      throw deny(StompRejectCode.FORBIDDEN);
    }
    requireParticipant(m.group(1), user);
  }

  private void requireParticipant(String roomId, AuthUser user) {
    if (!roomAccess.isParticipant(Long.parseLong(roomId), user.userId())) {
      throw deny(StompRejectCode.NOT_PARTICIPANT);
    }
  }

  private static AuthUser userOf(StompHeaderAccessor accessor) {
    Principal p = accessor.getUser();
    if (p instanceof StompPrincipal sp) {
      return sp.user();
    }
    throw deny(StompRejectCode.UNAUTHORIZED);
  }

  private static Rejected deny(StompRejectCode code) {
    return new Rejected(code);
  }

  /** 검사 중 거부를 preSend까지 전달하는 내부 신호. 밖으로 던지지 않는다. */
  private static final class Rejected extends RuntimeException {
    private final StompRejectCode code;

    Rejected(StompRejectCode code) {
      super(code.name(), null, false, false);
      this.code = code;
    }
  }
}
