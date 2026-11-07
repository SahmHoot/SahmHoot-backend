package com.sahmhoot.websocket.presence;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * 방 → 사용자 → 세션 접속 상태를 메모리에 관리한다(06 5절 접속 인원, #65).
 *
 * <p>세션은 그 방의 방 채널 구독이 하나 이상 남아 있는 동안 방에 접속한 것으로 본다. 구독·해제는
 * {@link RoomPresenceInterceptor}가 권한 검사 뒤에 전달하고, 끊김은 {@link SessionDisconnectEvent}로 받는다.
 * SessionSubscribeEvent는 쓰지 않는다. preserveReceiveOrder를 켜면 인터셉터가 거부한 구독에도 발행되기 때문이다.
 *
 * <p>끊김 신호는 수신 순서 보장 대기열을 거치지 않으므로, 대기열에 남아 있던 SUBSCRIBE가 끊김 처리 뒤에 도착할 수
 * 있다. 그래서 끊김을 처리할 때 WebSocket 세션 attributes에 종료 표식을 남기고, 구독을 반영하기 전에 같은 잠금
 * 안에서 표식을 확인한다. 같은 세션의 메시지는 모두 같은 attributes를 참조하므로 기간 제한 없이 막히고, 표식은
 * 세션과 함께 회수된다.
 *
 * <p>모든 변경은 잠금 하나로 직렬화한다. 사용자 단위 상태가 바뀐 경우에만 잠금 안에서
 * {@link RoomPresenceChangedEvent}를 동기 발행하므로, 같은 방의 알림은 상태 변경 순서대로 전달된다. 처리기는 방
 * 잠금·DB 조회 없이 빠르게 끝나야 하고 예외를 밖으로 던지지 않아야 한다. Spring 기본 멀티캐스터는 첫 예외에서 같은
 * 이벤트의 나머지 처리기 호출을 멈춘다. 추적기는 그 예외를 로그로 남기고 상태와 다른 방 처리는 계속한다.
 *
 * <p>조회 포트는 {@link RoomPresenceAdapter}가 따로 구현한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomPresenceTracker {

  /** 끊김을 처리한 세션의 attributes에 남기는 종료 표식. */
  static final String CLOSED_ATTRIBUTE = RoomPresenceTracker.class.getName() + ".CLOSED";

  private final ApplicationEventPublisher events;
  private final Clock clock;

  private final Object lock = new Object();
  private final Map<String, SessionEntry> sessions = new HashMap<>();
  private final Map<Long, Map<Long, UserEntry>> rooms = new HashMap<>();

  /**
   * 권한 검사를 통과한 방 채널 구독을 반영한다. 이미 끊김을 처리한 세션이거나 같은 구독 ID가 다시 오면 무시한다.
   *
   * @param sessionAttributes 그 세션의 WebSocket attributes. 끊김 뒤 늦게 도착한 구독을 걸러 내는 데 쓴다
   */
  public void subscribed(
      String sessionId,
      Map<String, Object> sessionAttributes,
      String subscriptionId,
      long userId,
      long roomId,
      boolean owner) {
    if (sessionId == null || subscriptionId == null) {
      return;
    }
    synchronized (lock) {
      if (sessionAttributes != null && sessionAttributes.containsKey(CLOSED_ATTRIBUTE)) {
        return;
      }
      SessionEntry session = sessions.computeIfAbsent(sessionId, id -> new SessionEntry(userId));
      if (session.roomBySubscription.putIfAbsent(subscriptionId, roomId) != null) {
        return;
      }
      RoomPresenceSnapshot before = snapshotOf(roomId);
      rooms
          .computeIfAbsent(roomId, id -> new HashMap<>())
          .computeIfAbsent(session.userId, id -> new UserEntry(owner))
          .sessionIds
          .add(sessionId);
      publishIfChanged(before, snapshotOf(roomId));
    }
  }

  /** 구독 해제를 반영한다. 같은 세션에 그 방의 다른 구독이 남아 있으면 세션은 방에 남는다. */
  public void unsubscribed(String sessionId, String subscriptionId) {
    if (sessionId == null || subscriptionId == null) {
      return;
    }
    synchronized (lock) {
      SessionEntry session = sessions.get(sessionId);
      if (session == null) {
        return;
      }
      Long roomId = session.roomBySubscription.remove(subscriptionId);
      if (roomId == null) {
        return;
      }
      if (session.roomBySubscription.isEmpty()) {
        sessions.remove(sessionId);
      }
      if (!session.roomBySubscription.containsValue(roomId)) {
        leave(sessionId, session.userId, roomId);
      }
    }
  }

  /**
   * 연결 종료를 반영한다. 추적 중인 세션이 아니어도 종료 표식은 남긴다(첫 구독보다 끊김이 먼저 처리된 경우). 같은
   * 세션의 끊김을 다시 받으면 지울 항목이 없으므로 아무것도 바뀌지 않는다.
   */
  public void disconnected(String sessionId, Map<String, Object> sessionAttributes) {
    if (sessionId == null) {
      return;
    }
    synchronized (lock) {
      if (sessionAttributes != null) {
        sessionAttributes.put(CLOSED_ATTRIBUTE, Boolean.TRUE);
      }
      SessionEntry session = sessions.remove(sessionId);
      if (session == null) {
        return;
      }
      for (Long roomId : new TreeSet<>(session.roomBySubscription.values())) {
        leave(sessionId, session.userId, roomId);
      }
    }
  }

  /** 정상 DISCONNECT, 탭 닫기, 하트비트 끊김 모두 이 이벤트로 들어온다. 한 세션에 여러 번 올 수 있다. */
  @EventListener
  public void onDisconnect(SessionDisconnectEvent event) {
    disconnected(
        event.getSessionId(),
        SimpMessageHeaderAccessor.getSessionAttributes(event.getMessage().getHeaders()));
  }

  /** 방의 사용자 단위 스냅샷. 구독 세션이 없으면 빈 집합과 ownerOnline=false를 반환한다. */
  public RoomPresenceSnapshot snapshot(long roomId) {
    synchronized (lock) {
      return snapshotOf(roomId);
    }
  }

  /** 방에 접속한 세션 수(탭 수). 테스트에서 처리 완료를 확인하는 데 쓴다. */
  int sessionCount(long roomId) {
    synchronized (lock) {
      Map<Long, UserEntry> users = rooms.get(roomId);
      return users == null ? 0 : users.values().stream().mapToInt(u -> u.sessionIds.size()).sum();
    }
  }

  private void leave(String sessionId, long userId, long roomId) {
    Map<Long, UserEntry> users = rooms.get(roomId);
    UserEntry user = users == null ? null : users.get(userId);
    if (user == null || !user.sessionIds.contains(sessionId)) {
      return;
    }
    RoomPresenceSnapshot before = snapshotOf(roomId);
    user.sessionIds.remove(sessionId);
    if (user.sessionIds.isEmpty()) {
      users.remove(userId);
    }
    if (users.isEmpty()) {
      rooms.remove(roomId);
    }
    publishIfChanged(before, snapshotOf(roomId));
  }

  private RoomPresenceSnapshot snapshotOf(long roomId) {
    Map<Long, UserEntry> users = rooms.get(roomId);
    if (users == null) {
      return new RoomPresenceSnapshot(roomId, Set.of(), false);
    }
    boolean ownerOnline = users.values().stream().anyMatch(user -> user.owner);
    return new RoomPresenceSnapshot(roomId, users.keySet(), ownerOnline);
  }

  /** 탭 하나만 닫히는 등 사용자 단위 상태가 같으면 발행하지 않는다. */
  private void publishIfChanged(RoomPresenceSnapshot before, RoomPresenceSnapshot after) {
    if (before.equals(after)) {
      return;
    }
    log.debug(
        "Room presence changed: roomId={}, users={}, ownerOnline={}",
        after.roomId(),
        after.onlineUserIds().size(),
        after.ownerOnline());
    try {
      events.publishEvent(new RoomPresenceChangedEvent(before, after, clock.instant()));
    } catch (RuntimeException e) {
      log.error("Room presence listener failed: roomId={}", after.roomId(), e);
    }
  }

  private static final class SessionEntry {
    private final long userId;
    /** 구독 ID → 방 ID. 한 세션이 같은 방의 여러 채널을 구독하면 여러 항목이 생긴다. */
    private final Map<String, Long> roomBySubscription = new HashMap<>();

    private SessionEntry(long userId) {
      this.userId = userId;
    }
  }

  private static final class UserEntry {
    private final boolean owner;
    private final Set<String> sessionIds = new HashSet<>();

    private UserEntry(boolean owner) {
      this.owner = owner;
    }
  }
}
