package com.sahmhoot.room.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.message.OnlineCountMessage;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.room.service.port.RoomTaskCanceller;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 방마다 1초 동안 변경을 모으고 발행 시점의 최신 접속 인원을 읽는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoomPresencePublisher implements RoomTaskCanceller {
  private final RoomPresenceService presence;
  private final RoomPresenceTaskScheduler scheduler;
  private final RoomLockManager locks;
  private final SimpMessagingTemplate messaging;
  private final Clock clock;
  private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void changed(RoomPresenceChangedEvent event) {
    // #65가 내부 잠금을 잡고 동기 알림을 보낼 수 있다. 여기서는 방 잠금·DB·#65 조회를 하지 않는다.
    pending.compute(event.current().roomId(), (roomId, existing) -> {
      Pending state = existing != null ? existing : new Pending(event.previous());
      state.dirty = true;
      if (state.future == null && !state.running) schedule(roomId, state);
      return state;
    });
  }

  private void schedule(Long roomId, Pending state) {
    state.future = scheduler.schedule(clock.instant().plusSeconds(1), () -> flush(roomId, state));
  }

  private void flush(Long roomId, Pending expected) {
    locks.executeWithLock(roomId, () -> {
      var started = new AtomicBoolean();
      pending.computeIfPresent(roomId, (id, state) -> {
        if (state == expected) {
          state.future = null;
          state.running = true;
          state.dirty = false;
          started.set(true);
        }
        return state;
      });
      if (!started.get()) return;
      try {
        // compute를 벗어난 뒤 #65를 조회한다. #65 이벤트 콜백과의 잠금 역전을 피한다.
        var change = presence.countChange(expected.previous);
        if (change.isEmpty()) { cancelAll(roomId); return; }
        int before = expected.lastCount != null ? expected.lastCount : change.get().previous();
        int count = change.get().current();
        if (before != count) {
          messaging.convertAndSend("/topic/rooms/" + roomId + "/presence",
              OnlineCountMessage.of(clock.instant(), count));
        }
        expected.lastCount = count;
      } catch (RuntimeException error) {
        log.error("Room presence publication failed: roomId={}", roomId, error);
      } finally {
        pending.computeIfPresent(roomId, (id, state) -> {
          if (state != expected) return state;
          state.running = false;
          if (!state.dirty) return null;
          // 발행 도중 생긴 변경은 발행이 끝난 뒤부터 다시 1초를 기다린다.
          schedule(roomId, state);
          return state;
        });
      }
    });
  }

  @Override public void cancelAll(Long roomId) {
    pending.computeIfPresent(roomId, (id, state) -> {
      if (state.future != null) state.future.cancel(false);
      return null;
    });
  }

  @PreDestroy void shutdown() { pending.keySet().forEach(this::cancelAll); }

  private static class Pending {
    final RoomPresenceSnapshot previous;
    ScheduledFuture<?> future;
    Integer lastCount;
    boolean running;
    boolean dirty;
    Pending(RoomPresenceSnapshot previous) { this.previous = previous; }
  }
}
