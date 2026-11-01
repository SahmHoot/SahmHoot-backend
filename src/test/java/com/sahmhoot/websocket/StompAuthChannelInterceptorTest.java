package com.sahmhoot.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.auth.jwt.JwtProperties;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.user.Role;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

class StompAuthChannelInterceptorTest {

  private final JwtProvider jwt =
      new JwtProvider(new JwtProperties("test-only-jwt-secret-0123456789abcdefghijklmnop", 14));

  /** 사용자 1은 방 10의 소유자, 사용자 2는 방 10의 참여자. 방 20에는 아무도 없음. */
  private final RoomAccessChecker access =
      new RoomAccessChecker(null) {
        @Override
        public boolean isParticipant(long roomId, long userId) {
          return roomId == 10 && (userId == 1 || userId == 2);
        }

        @Override
        public boolean isOwner(long roomId, long userId) {
          return roomId == 10 && userId == 1;
        }
      };

  /** 보낸 거부 알림을 "CONNECT:코드" 또는 "사용자:코드"로 기록한다. */
  private final List<String> rejected = new ArrayList<>();

  private final StompRejectionSender rejection =
      new StompRejectionSender(null, null) {
        @Override
        public void rejectConnect(String sessionId, String code) {
          rejected.add("CONNECT:" + code);
        }

        @Override
        public void rejectFrame(String userName, String sessionId, String code, String message) {
          rejected.add(userName + ":" + code);
        }
      };

  private final StompAuthChannelInterceptor interceptor =
      new StompAuthChannelInterceptor(jwt, access, rejection);

  private Message<?> frame(StompCommand cmd, Long userId, String dest, String auth) {
    StompHeaderAccessor a = StompHeaderAccessor.create(cmd);
    if (userId != null) {
      a.setUser(new StompPrincipal(new AuthUser(userId, Role.STUDENT)));
    }
    if (dest != null) {
      a.setDestination(dest);
    }
    if (auth != null) {
      a.addNativeHeader("Authorization", auth);
    }
    a.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
  }

  private void allowed(Message<?> m) {
    assertThat(interceptor.preSend(m, null)).isSameAs(m);
    assertThat(rejected).isEmpty();
  }

  /** 프레임을 버리고(null) 기대한 알림 하나만 보냈는지 확인한다. */
  private void rejected(Message<?> m, String expected) {
    assertThat(interceptor.preSend(m, null)).isNull();
    assertThat(rejected).containsExactly(expected);
    rejected.clear();
  }

  @Test
  void CONNECT는_유효한_JWT만_허용() {
    String token = jwt.issue(2L, Role.STUDENT).accessToken();
    allowed(frame(StompCommand.CONNECT, null, null, "Bearer " + token));
    rejected(frame(StompCommand.CONNECT, null, null, null), "CONNECT:UNAUTHORIZED");
    rejected(frame(StompCommand.CONNECT, null, null, "Bearer bad"), "CONNECT:UNAUTHORIZED");
  }

  @Test
  void 참여자는_자기_방_채널만_구독() {
    allowed(frame(StompCommand.SUBSCRIBE, 2L, "/topic/rooms/10/quiz", null));
    allowed(frame(StompCommand.SUBSCRIBE, 2L, "/user/queue/errors", null));
    rejected(frame(StompCommand.SUBSCRIBE, 2L, "/topic/rooms/20/quiz", null), "2:NOT_PARTICIPANT");
    rejected(frame(StompCommand.SUBSCRIBE, 2L, "/topic/anything", null), "2:FORBIDDEN");
  }

  @Test
  void stats는_소유자만() {
    allowed(frame(StompCommand.SUBSCRIBE, 1L, "/user/queue/rooms/10/stats", null));
    rejected(frame(StompCommand.SUBSCRIBE, 2L, "/user/queue/rooms/10/stats", null), "2:FORBIDDEN");
  }

  @Test
  void topic으로의_SEND는_거부() {
    allowed(frame(StompCommand.SEND, 2L, "/app/rooms/10/chat", null));
    rejected(frame(StompCommand.SEND, 2L, "/topic/rooms/10/quiz", null), "2:FORBIDDEN");
    rejected(frame(StompCommand.SEND, 2L, "/app/rooms/20/chat", null), "2:NOT_PARTICIPANT");
  }
}
