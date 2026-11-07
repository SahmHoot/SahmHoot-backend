package com.sahmhoot.websocket.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.messaging.simp.stomp.StompCommand.SUBSCRIBE;
import static org.springframework.messaging.simp.stomp.StompCommand.UNSUBSCRIBE;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.auth.jwt.JwtProperties;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.common.ratelimit.RateLimiter;
import com.sahmhoot.user.Role;
import com.sahmhoot.websocket.RoomAccessChecker;
import com.sahmhoot.websocket.StompAuthChannelInterceptor;
import com.sahmhoot.websocket.StompPrincipal;
import com.sahmhoot.websocket.StompRejectionSender;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

class RoomPresenceInterceptorTest {

  /** 사용자 1은 방 10의 소유자, 사용자 2는 방 10의 참여자. 사용자 3은 어느 방에도 없다. */
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

  private final List<RoomPresenceChangedEvent> events = new ArrayList<>();
  private final RoomPresenceTracker tracker =
      new RoomPresenceTracker(
          event -> events.add((RoomPresenceChangedEvent) event), Clock.systemUTC());
  private final RoomPresenceInterceptor interceptor = new RoomPresenceInterceptor(tracker, access);

  /** 세션별 WebSocket attributes. 실제처럼 같은 세션의 프레임은 같은 Map을 공유한다. */
  private final Map<String, Map<String, Object>> attributes = new HashMap<>();

  private Map<String, Object> attributesOf(String sessionId) {
    return attributes.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>());
  }

  private Message<?> frame(
      StompCommand cmd, long userId, String sessionId, String subscriptionId, String dest) {
    StompHeaderAccessor a = StompHeaderAccessor.create(cmd);
    a.setUser(new StompPrincipal(new AuthUser(userId, Role.STUDENT)));
    a.setSessionId(sessionId);
    a.setSessionAttributes(attributesOf(sessionId));
    a.setSubscriptionId(subscriptionId);
    if (dest != null) {
      a.setDestination(dest);
    }
    a.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
  }

  @Test
  void 방_채널_구독만_추적하고_오류_채널은_추적하지_않는다() {
    Message<?> quiz = frame(SUBSCRIBE, 2L, "s1", "sub-0", "/topic/rooms/10/quiz");
    assertThat(interceptor.preSend(quiz, null)).isSameAs(quiz);
    interceptor.preSend(frame(SUBSCRIBE, 2L, "s1", "sub-1", "/user/queue/rooms/10/me"), null);
    interceptor.preSend(frame(SUBSCRIBE, 2L, "s1", "sub-2", "/user/queue/errors"), null);

    assertThat(tracker.snapshot(10L)).isEqualTo(new RoomPresenceSnapshot(10L, Set.of(2L), false));
    assertThat(tracker.sessionCount(10L)).isEqualTo(1);
    assertThat(events).hasSize(1);
  }

  @Test
  void 소유자_여부는_방_소유자_조회로_정한다() {
    interceptor.preSend(frame(SUBSCRIBE, 1L, "s1", "sub-0", "/user/queue/rooms/10/stats"), null);

    assertThat(tracker.snapshot(10L)).isEqualTo(new RoomPresenceSnapshot(10L, Set.of(1L), true));
  }

  @Test
  void UNSUBSCRIBE는_그_구독만_해제한다() {
    interceptor.preSend(frame(SUBSCRIBE, 2L, "s1", "sub-0", "/topic/rooms/10/quiz"), null);
    interceptor.preSend(frame(SUBSCRIBE, 2L, "s1", "sub-1", "/topic/rooms/10/chat"), null);

    interceptor.preSend(frame(UNSUBSCRIBE, 2L, "s1", "sub-0", null), null);
    assertThat(tracker.snapshot(10L).onlineUserIds()).containsExactly(2L);

    interceptor.preSend(frame(UNSUBSCRIBE, 2L, "s1", "sub-1", null), null);
    assertThat(tracker.snapshot(10L).onlineUserIds()).isEmpty();
  }

  /** WebSocketConfig와 같은 순서로 등록한 채널에서, 권한 검사가 거부한 구독은 추적까지 가지 않는다. */
  @Test
  void 권한_검사에서_거부된_구독은_추적하지_않는다() {
    JwtProvider jwt =
        new JwtProvider(new JwtProperties("test-only-jwt-secret-0123456789abcdefghijklmnop", 14));
    StompRejectionSender ignoreRejection =
        new StompRejectionSender(null, null) {
          @Override
          public void rejectConnect(String sessionId, String code) {}

          @Override
          public void rejectFrame(String userName, String sessionId, String code, String message) {}
        };
    StompAuthChannelInterceptor auth =
        new StompAuthChannelInterceptor(jwt, access, ignoreRejection, new RateLimiter());
    ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
    inbound.setInterceptors(List.of(auth, interceptor));

    inbound.send(frame(SUBSCRIBE, 3L, "s3", "sub-0", "/topic/rooms/10/quiz"));
    inbound.send(frame(SUBSCRIBE, 2L, "s2", "sub-0", "/topic/rooms/10/quiz"));

    assertThat(tracker.snapshot(10L).onlineUserIds()).containsExactly(2L);
  }

  /** 소유자 조회(SQL)는 추적기 잠금 밖에서 하므로, 그 사이 끊김이 처리되면 구독을 반영하지 않아야 한다. */
  @Test
  void 소유자_조회_중에_끊긴_세션의_구독은_반영하지_않는다() throws Exception {
    CountDownLatch querying = new CountDownLatch(1);
    CountDownLatch resume = new CountDownLatch(1);
    RoomAccessChecker slow =
        new RoomAccessChecker(null) {
          @Override
          public boolean isOwner(long roomId, long userId) {
            querying.countDown();
            try {
              resume.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            return false;
          }
        };
    RoomPresenceInterceptor slowInterceptor = new RoomPresenceInterceptor(tracker, slow);
    Message<?> quiz = frame(SUBSCRIBE, 2L, "s1", "sub-0", "/topic/rooms/10/quiz");

    Thread inbound = new Thread(() -> slowInterceptor.preSend(quiz, null));
    inbound.start();
    assertThat(querying.await(5, TimeUnit.SECONDS)).isTrue();
    tracker.disconnected("s1", attributesOf("s1"));
    resume.countDown();
    inbound.join(5_000);

    assertThat(inbound.isAlive()).isFalse();
    assertThat(tracker.snapshot(10L).onlineUserIds()).isEmpty();
    assertThat(events).isEmpty();
  }

  @Test
  void 추적_중_오류가_나도_프레임은_그대로_통과한다() {
    RoomAccessChecker broken =
        new RoomAccessChecker(null) {
          @Override
          public boolean isOwner(long roomId, long userId) {
            throw new IllegalStateException("db unavailable");
          }
        };
    RoomPresenceInterceptor failing = new RoomPresenceInterceptor(tracker, broken);
    Message<?> quiz = frame(SUBSCRIBE, 2L, "s1", "sub-0", "/topic/rooms/10/quiz");

    assertThat(failing.preSend(quiz, null)).isSameAs(quiz);
    assertThat(tracker.snapshot(10L).onlineUserIds()).isEmpty();
  }
}
