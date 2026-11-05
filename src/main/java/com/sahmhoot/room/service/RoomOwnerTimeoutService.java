package com.sahmhoot.room.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.room.service.port.RoomTaskCanceller;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** #65의 소유자 연결 변화로 30분 종료를 예약한다. 세션 추적·서버 재시작 복구는 담당하지 않는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoomOwnerTimeoutService implements RoomTaskCanceller {
  private static final Duration TIMEOUT = Duration.ofMinutes(30);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
  private final RoomPresenceTaskScheduler scheduler;
  private final RoomLockManager locks;
  private final ObjectProvider<RoomPresencePort> sources;
  private final RoomRepository rooms;
  private final RoomManagementService management;
  private final Clock clock;
  private final Map<Long, Watch> watches = new ConcurrentHashMap<>();
  private volatile boolean stopping;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void changed(RoomPresenceChangedEvent event) {
    if (!event.ownerConnectionChanged() || stopping) return;
    // #65가 내부 잠금을 잡고 동기 알림을 보낼 수 있으므로 여기서는 방 잠금·DB 조회를 하지 않는다.
    watches.compute(event.current().roomId(), (roomId, existing) -> {
      if (stopping) return null;
      Watch watch = existing != null ? existing : new Watch();
      if (watch.changedAt != null && event.occurredAt().isBefore(watch.changedAt)) return watch;
      boolean online = event.current().ownerOnline();
      if (watch.changedAt != null && watch.online == online) return watch;
      watch.changedAt = event.occurredAt();
      watch.online = online;
      if (online) {
        cancel(watch.pending);
        watch.pending = null;
      } else {
        Instant deadline = event.occurredAt().plus(TIMEOUT);
        schedule(roomId, watch, deadline, deadline);
      }
      return watch;
    });
  }

  private void schedule(Long roomId, Watch watch, Instant deadline, Instant at) {
    var pending = new Pending(deadline);
    pending.future = scheduler.schedule(at, () -> expire(roomId, pending));
    watch.pending = pending;
  }

  private boolean active(Long roomId, Pending expected) {
    var active = new AtomicBoolean();
    watches.computeIfPresent(roomId, (id, watch) -> {
      active.set(watch.pending == expected);
      return watch;
    });
    return active.get();
  }

  private void expire(Long roomId, Pending expected) {
    locks.executeWithLock(roomId, () -> {
      if (!active(roomId, expected)) return;
      if (clock.instant().isBefore(expected.deadline)) {
        reschedule(roomId, expected, expected.deadline);
        return;
      }
      try {
        var room = rooms.findById(roomId);
        if (room.isEmpty() || room.get().getStatus() == RoomStatus.CLOSED) {
          cancelAll(roomId);
          return;
        }
        // 이벤트 전달이 늦더라도 만료 시점의 실제 접속을 확인해 잘못된 종료를 막는다.
        var current = Objects.requireNonNull(sources.getObject().getPresence(roomId), "Presence snapshot is required");
        if (!roomId.equals(current.roomId())
            || current.ownerOnline() != current.onlineUserIds().contains(room.get().getHostId())) {
          throw new IllegalStateException("소유자 접속 스냅샷이 일치하지 않습니다.");
        }
        if (current.ownerOnline()) {
          watches.computeIfPresent(roomId, (id, watch) -> {
            if (watch.pending == expected) {
              watch.online = true;
              watch.pending = null;
            }
            return watch;
          });
          return;
        }
        if (!active(roomId, expected)) return;
        // 잠금 안·트랜잭션 밖에서 기존 종료 처리를 호출한다. 종료 후 예약 취소 훅도 그대로 실행된다.
        management.close(roomId, room.get().getHostId());
        cancelAll(roomId);
      } catch (RuntimeException error) {
        log.error("Owner timeout close failed; retrying: roomId={}", roomId, error);
        reschedule(roomId, expected, clock.instant().plus(RETRY_DELAY));
      }
    });
  }

  private void reschedule(Long roomId, Pending expected, Instant at) {
    watches.computeIfPresent(roomId, (id, watch) -> {
      if (watch.pending == expected && !stopping) schedule(roomId, watch, expected.deadline, at);
      return watch;
    });
  }

  @Override public void cancelAll(Long roomId) {
    watches.computeIfPresent(roomId, (id, watch) -> {
      cancel(watch.pending);
      return null;
    });
  }

  private void cancel(Pending pending) {
    if (pending != null && pending.future != null) pending.future.cancel(false);
  }

  @PreDestroy void shutdown() {
    stopping = true;
    watches.keySet().forEach(this::cancelAll);
  }

  private static class Watch {
    Instant changedAt;
    boolean online;
    Pending pending;
  }

  private static class Pending {
    final Instant deadline;
    ScheduledFuture<?> future;
    Pending(Instant deadline) { this.deadline = deadline; }
  }
}
