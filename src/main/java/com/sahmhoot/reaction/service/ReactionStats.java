package com.sahmhoot.reaction.service;

import com.sahmhoot.quiz.service.port.RoomLockPort;
import com.sahmhoot.reaction.dto.message.ReactionStatsMessage;
import com.sahmhoot.room.service.port.RoomReactionStatsPort;
import com.sahmhoot.room.service.port.RoomTaskCanceller;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 방마다 최근 60초 반응을 서버 메모리에 모아 소유자에게 이모지별 개수를 알린다(RE-03·05). 서버 1대 전제.
 *
 * <ul>
 *   <li>DB에 저장하지 않는다. 서버를 다시 시작하면 집계는 0부터 시작한다.
 *   <li>새 반응은 최소 0.5초 간격으로 묶어 REACTION_STATS로 보낸다.
 *   <li>반응이 60초를 지나 빠질 때도 줄어든 값을 보낸다. 교수 화면은 받은 값으로 덮어쓰기만 한다.
 *   <li>모든 변경과 조회는 같은 방 잠금 안에서 한다. 전송, 예약 실행, state 조회, 방 종료 정리가 서로 끼어들지 않는다.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionStats implements RoomReactionStatsPort, RoomTaskCanceller {
  /** 허용 이모지와 집계 순서(이슈 #59, RE-01). */
  public static final List<String> EMOJIS = List.of("👍", "❓", "😀", "👏");
  static final Duration WINDOW = Duration.ofSeconds(60);
  static final Duration MIN_INTERVAL = Duration.ofMillis(500);

  private final RoomLockPort locks;
  private final ReactionTaskScheduler scheduler;
  private final SimpMessagingTemplate messaging;
  private final Clock clock;
  private final Map<Long, Window> windows = new ConcurrentHashMap<>();
  private volatile boolean stopping;

  /** 방 잠금 안에서 호출한다. */
  void record(Long roomId, Long ownerId, String emoji, Instant now) {
    int index = EMOJIS.indexOf(emoji);
    if (index < 0) throw new IllegalArgumentException("허용되지 않은 이모지");
    Window window = windows.computeIfAbsent(roomId, id -> new Window(ownerId));
    window.prune(now);
    window.add(now, index);
    reschedule(roomId, window, now);
  }

  /** state의 reactionStats. 방 잠금 안에서 state의 serverTime을 기준 시각으로 받아 호출한다. */
  @Override
  public Map<String, Integer> recentCounts(Long roomId, Instant at) {
    Window window = windows.get(roomId);
    if (window == null) return snapshot(new int[EMOJIS.size()]);
    // 여기서 만료 항목을 지워도 dirty가 남아 예약 실행이 줄어든 값을 보낸다.
    window.prune(at);
    reschedule(roomId, window, at);
    return snapshot(window.counts);
  }

  /** 방 종료 시 방 잠금 안에서 호출된다. 이후 실행되는 예약은 창이 없어 아무것도 하지 않는다. */
  @Override
  public void cancelAll(Long roomId) {
    Window window = windows.remove(roomId);
    if (window != null) window.cancel();
  }

  @PreDestroy
  void shutdown() {
    stopping = true;
    windows.values().forEach(Window::cancel);
    windows.clear();
  }

  private void reschedule(Long roomId, Window window, Instant now) {
    if (stopping) return;
    Instant at = window.nextRunAt(now);
    if (at == null) return;
    // 더 이른 예약이 있으면 그대로 둔다. 실행될 때 다음 예약을 다시 정한다.
    if (window.future != null && !window.scheduledAt.isAfter(at)) return;
    window.cancel();
    long generation = ++window.generation;
    window.scheduledAt = at;
    try {
      window.future = scheduler.schedule(at,
          () -> locks.executeWithLock(roomId, () -> flush(roomId, window, generation)));
    } catch (RuntimeException failure) {
      window.scheduledAt = null;
      log.error("Failed to schedule reaction stats for roomId={}", roomId, failure);
    }
  }

  private void flush(Long roomId, Window expected, long generation) {
    // cancel(false)로는 이미 잠금을 기다리던 콜백을 멈출 수 없어 창과 세대를 함께 확인한다.
    if (stopping || windows.get(roomId) != expected || expected.generation != generation) return;
    Window window = expected;
    window.future = null;
    window.scheduledAt = null;
    Instant now = clock.instant();
    window.prune(now);
    if (window.dirty && window.canSendAt(now)) {
      window.lastSentAt = now;
      try {
        messaging.convertAndSendToUser(window.ownerId.toString(), "/queue/rooms/" + roomId + "/stats",
            ReactionStatsMessage.of(snapshot(window.counts), now));
        window.dirty = false;
      } catch (RuntimeException failure) {
        // dirty를 남겨 0.5초 뒤 다시 보낸다. 마지막 0 집계도 보내기 전까지 창을 지우지 않는다.
        log.error("Failed to send reaction stats; retrying for roomId={}", roomId, failure);
      }
    }
    // 마지막 전송 뒤 0.5초가 지나야 지운다. 바로 지우면 곧이어 온 반응이 간격 없이 전송된다.
    if (window.isIdle() && window.canSendAt(now)) {
      windows.remove(roomId);
      return;
    }
    reschedule(roomId, window, now);
  }

  private static Map<String, Integer> snapshot(int[] counts) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (int i = 0; i < EMOJIS.size(); i++) result.put(EMOJIS.get(i), counts[i]);
    return result;
  }

  private record Entry(Instant at, int index) {}

  /** 한 방의 집계 상태. 방 잠금 안에서만 읽고 바꾼다. */
  private static final class Window {
    private final Long ownerId;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final int[] counts = new int[EMOJIS.size()];
    private boolean dirty;
    private Instant lastSentAt;
    private ScheduledFuture<?> future;
    private Instant scheduledAt;
    private long generation;

    private Window(Long ownerId) {
      this.ownerId = ownerId;
    }

    private void add(Instant at, int index) {
      entries.addLast(new Entry(at, index));
      counts[index]++;
      dirty = true;
    }

    /** 60초가 지난 반응(at + 60초 ≤ now)을 뺀다. 뺀 것이 있으면 보낼 값이 바뀐 것이다. */
    private void prune(Instant now) {
      while (!entries.isEmpty() && !entries.peekFirst().at().plus(WINDOW).isAfter(now)) {
        counts[entries.pollFirst().index()]--;
        dirty = true;
      }
    }

    private boolean canSendAt(Instant now) {
      return lastSentAt == null || !now.isBefore(lastSentAt.plus(MIN_INTERVAL));
    }

    /**
     * 바뀐 값이 있으면 간격이 허락하는 가장 이른 시각, 없으면 가장 오래된 반응이 빠지는 시각.
     * 비어 있으면 창을 지울 수 있는 시각(마지막 전송 + 0.5초)이고, 이미 지울 수 있으면 null.
     */
    private Instant nextRunAt(Instant now) {
      boolean cooled = lastSentAt == null || canSendAt(now);
      Instant earliest = cooled ? now : lastSentAt.plus(MIN_INTERVAL);
      if (dirty) return earliest;
      if (entries.isEmpty()) return cooled ? null : earliest;
      Instant expiry = entries.peekFirst().at().plus(WINDOW);
      return expiry.isAfter(earliest) ? expiry : earliest;
    }

    private boolean isIdle() {
      return entries.isEmpty() && !dirty;
    }

    private void cancel() {
      if (future != null) future.cancel(false);
      future = null;
      scheduledAt = null;
    }
  }
}
