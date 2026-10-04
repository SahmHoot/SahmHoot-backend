package com.sahmhoot.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.auth.jwt.JwtProperties;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
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

  private final StompAuthChannelInterceptor interceptor =
      new StompAuthChannelInterceptor(jwt, access);

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

  private void send(Message<?> m) {
    interceptor.preSend(m, null);
  }

  @Test
  void CONNECT는_유효한_JWT만_허용() {
    String token = jwt.issue(2L, Role.STUDENT).accessToken();
    assertThatCode(() -> send(frame(StompCommand.CONNECT, null, null, "Bearer " + token)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, null, null, null)))
        .isInstanceOf(MessageDeliveryException.class);
    assertThatThrownBy(() -> send(frame(StompCommand.CONNECT, null, null, "Bearer bad")))
        .isInstanceOf(MessageDeliveryException.class);
  }

  @Test
  void 참여자는_자기_방_채널만_구독() {
    assertThatCode(() -> send(frame(StompCommand.SUBSCRIBE, 2L, "/topic/rooms/10/quiz", null)))
        .doesNotThrowAnyException();
    assertThatCode(() -> send(frame(StompCommand.SUBSCRIBE, 2L, "/user/queue/errors", null)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> send(frame(StompCommand.SUBSCRIBE, 2L, "/topic/rooms/20/quiz", null)))
        .isInstanceOf(MessageDeliveryException.class);
    assertThatThrownBy(() -> send(frame(StompCommand.SUBSCRIBE, 2L, "/topic/anything", null)))
        .isInstanceOf(MessageDeliveryException.class);
  }

  @Test
  void stats는_소유자만() {
    assertThatCode(
            () -> send(frame(StompCommand.SUBSCRIBE, 1L, "/user/queue/rooms/10/stats", null)))
        .doesNotThrowAnyException();
    assertThatThrownBy(
            () -> send(frame(StompCommand.SUBSCRIBE, 2L, "/user/queue/rooms/10/stats", null)))
        .isInstanceOf(MessageDeliveryException.class);
  }

  @Test
  void topic으로의_SEND는_거부() {
    assertThatCode(() -> send(frame(StompCommand.SEND, 2L, "/app/rooms/10/chat", null)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> send(frame(StompCommand.SEND, 2L, "/topic/rooms/10/quiz", null)))
        .isInstanceOf(MessageDeliveryException.class);
    assertThatThrownBy(() -> send(frame(StompCommand.SEND, 2L, "/app/rooms/20/chat", null)))
        .isInstanceOf(MessageDeliveryException.class);
  }
}
