package com.sahmhoot.websocket.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.quiz.schedule.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RoomPresenceTrackerTest {

  private static final Instant T0 = Instant.parse("2026-11-07T07:00:00Z");
  private static final long ROOM = 10L;
  private static final long OTHER_ROOM = 20L;
  private static final long OWNER = 100L;
  private static final long STUDENT = 201L;

  private final MutableClock clock = new MutableClock(T0);

  /** 발행된 이벤트를 순서대로 기록한다. */
  private final List<RoomPresenceChangedEvent> events = new ArrayList<>();

  private final RoomPresenceTracker tracker =
      new RoomPresenceTracker(event -> events.add((RoomPresenceChangedEvent) event), clock);

  /** 세션별 WebSocket attributes. 실제처럼 같은 세션의 메시지는 같은 Map을 공유한다. */
  private final Map<String, Map<String, Object>> attributes = new HashMap<>();

  private Map<String, Object> attributesOf(String sessionId) {
    return attributes.computeIfAbsent(sessionId, id -> new ConcurrentHashMap<>());
  }

  private void subscribe(String sessionId, String subscriptionId, long userId, long roomId) {
    tracker.subscribed(
        sessionId, attributesOf(sessionId), subscriptionId, userId, roomId, userId == OWNER);
  }

  private void disconnect(String sessionId) {
    tracker.disconnected(sessionId, attributesOf(sessionId));
  }

  @Test
  void 같은_사용자의_탭_2개는_1명이고_마지막_탭을_닫아야_빠진다() {
    subscribe("tab-1", "sub-0", STUDENT, ROOM);
    subscribe("tab-2", "sub-0", STUDENT, ROOM);

    assertThat(tracker.snapshot(ROOM).onlineUserIds()).containsExactly(STUDENT);
    assertThat(tracker.sessionCount(ROOM)).isEqualTo(2);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).occurredAt()).isEqualTo(T0);

    disconnect("tab-1");
    assertThat(tracker.snapshot(ROOM).onlineUserIds()).containsExactly(STUDENT);
    assertThat(events).hasSize(1);

    disconnect("tab-2");
    assertThat(tracker.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(events).hasSize(2);
    assertThat(events.get(1).previous().onlineUserIds()).containsExactly(STUDENT);
    assertThat(events.get(1).current().onlineUserIds()).isEmpty();
  }

  @Test
  void 같은_세션의_끊김을_여러_번_받아도_한_번만_반영한다() {
    subscribe("tab-1", "sub-0", STUDENT, ROOM);

    disconnect("tab-1");
    disconnect("tab-1");
    disconnect("tab-1");
    tracker.disconnected("tab-1", null);

    assertThat(tracker.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(events).hasSize(2);
  }

  @Test
  void 소유자는_집합에_포함되고_마지막_소유자_탭이_닫힐_때만_연결_변화로_알린다() {
    subscribe("owner-1", "sub-0", OWNER, ROOM);
    subscribe("owner-2", "sub-0", OWNER, ROOM);
    subscribe("student-1", "sub-0", STUDENT, ROOM);

    assertThat(events).hasSize(2);
    assertThat(events.get(0).ownerConnectionChanged()).isTrue();
    assertThat(events.get(0).current().ownerOnline()).isTrue();
    assertThat(events.get(1).ownerConnectionChanged()).isFalse();
    assertThat(tracker.snapshot(ROOM))
        .isEqualTo(new RoomPresenceSnapshot(ROOM, Set.of(OWNER, STUDENT), true));

    disconnect("owner-1");
    assertThat(events).hasSize(2);

    disconnect("owner-2");
    assertThat(events).hasSize(3);
    assertThat(events.get(2).ownerConnectionChanged()).isTrue();
    assertThat(events.get(2).current())
        .isEqualTo(new RoomPresenceSnapshot(ROOM, Set.of(STUDENT), false));
  }

  @Test
  void 한_세션이_같은_방_채널을_여러_개_구독하면_전부_해제해야_빠진다() {
    subscribe("tab-1", "sub-quiz", STUDENT, ROOM);
    subscribe("tab-1", "sub-chat", STUDENT, ROOM);
    assertThat(tracker.sessionCount(ROOM)).isEqualTo(1);
    assertThat(events).hasSize(1);

    tracker.unsubscribed("tab-1", "sub-quiz");
    assertThat(tracker.snapshot(ROOM).onlineUserIds()).containsExactly(STUDENT);
    assertThat(events).hasSize(1);

    tracker.unsubscribed("tab-1", "sub-chat");
    tracker.unsubscribed("tab-1", "sub-chat");
    assertThat(tracker.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(events).hasSize(2);
  }

  @Test
  void 같은_구독_ID로_다시_구독해도_한_번만_반영한다() {
    subscribe("tab-1", "sub-0", STUDENT, ROOM);
    subscribe("tab-1", "sub-0", STUDENT, ROOM);

    tracker.unsubscribed("tab-1", "sub-0");

    assertThat(tracker.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(events).hasSize(2);
  }

  @Test
  void 끊긴_세션의_늦은_구독은_반영하지_않는다() {
    subscribe("tab-1", "sub-0", STUDENT, ROOM);
    disconnect("tab-1");

    subscribe("tab-1", "sub-1", STUDENT, ROOM);

    assertThat(tracker.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(events).hasSize(2);
  }

  @Test
  void 첫_구독보다_끊김이_먼저_처리되어도_종료_표식이_남아_늦은_구독을_막는다() {
    disconnect("tab-1");
    assertThat(attributesOf("tab-1")).containsKey(RoomPresenceTracker.CLOSED_ATTRIBUTE);

    subscribe("tab-1", "sub-0", STUDENT, ROOM);

    assertThat(tracker.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(events).isEmpty();
  }

  @Test
  void 방마다_따로_집계하고_한_세션의_끊김은_방마다_알린다() {
    subscribe("tab-1", "sub-0", STUDENT, ROOM);
    subscribe("tab-1", "sub-1", STUDENT, OTHER_ROOM);
    assertThat(tracker.snapshot(OTHER_ROOM).onlineUserIds()).containsExactly(STUDENT);
    events.clear();

    disconnect("tab-1");

    assertThat(events).extracting(e -> e.current().roomId()).containsExactly(ROOM, OTHER_ROOM);
    assertThat(events).allSatisfy(e -> assertThat(e.current().onlineUserIds()).isEmpty());
  }

  @Test
  void 구독이_없는_방은_빈_스냅샷을_반환한다() {
    assertThat(tracker.snapshot(99L)).isEqualTo(new RoomPresenceSnapshot(99L, Set.of(), false));
  }

  @Test
  void 이벤트_처리기가_실패해도_추적_상태는_유지된다() {
    RoomPresenceTracker failing =
        new RoomPresenceTracker(
            event -> {
              throw new IllegalStateException("listener failure");
            },
            clock);

    failing.subscribed("tab-1", attributesOf("tab-1"), "sub-0", STUDENT, ROOM, false);
    assertThat(failing.snapshot(ROOM).onlineUserIds()).containsExactly(STUDENT);

    failing.disconnected("tab-1", attributesOf("tab-1"));
    assertThat(failing.snapshot(ROOM).onlineUserIds()).isEmpty();
  }

  /** 이벤트는 잠금 안에서 발행되므로 앞 이벤트의 current가 다음 이벤트의 previous와 같아야 한다. */
  @Test
  void 여러_스레드에서_구독과_끊김이_겹쳐도_이벤트는_상태_변경_순서대로_이어진다() throws Exception {
    List<RoomPresenceChangedEvent> recorded = Collections.synchronizedList(new ArrayList<>());
    RoomPresenceTracker concurrent =
        new RoomPresenceTracker(event -> recorded.add((RoomPresenceChangedEvent) event), clock);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < 400; i++) {
        String sessionId = "tab-" + i;
        boolean owner = i % 50 == 0;
        long userId = owner ? OWNER : 300L + (i % 20);
        Map<String, Object> sessionAttributes = new ConcurrentHashMap<>();
        tasks.add(
            pool.submit(
                () -> {
                  concurrent.subscribed(sessionId, sessionAttributes, "sub-0", userId, ROOM, owner);
                  concurrent.snapshot(ROOM);
                  concurrent.disconnected(sessionId, sessionAttributes);
                }));
      }
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(concurrent.snapshot(ROOM).onlineUserIds()).isEmpty();
    assertThat(concurrent.sessionCount(ROOM)).isZero();
    assertThat(recorded).isNotEmpty();
    assertThat(recorded.get(0).previous().onlineUserIds()).isEmpty();
    for (int i = 1; i < recorded.size(); i++) {
      assertThat(recorded.get(i).previous()).isEqualTo(recorded.get(i - 1).current());
    }
    assertThat(recorded.get(recorded.size() - 1).current().onlineUserIds()).isEmpty();
    assertThat(recorded)
        .allSatisfy(
            e ->
                assertThat(e.current().ownerOnline())
                    .isEqualTo(e.current().onlineUserIds().contains(OWNER)));
  }
}
