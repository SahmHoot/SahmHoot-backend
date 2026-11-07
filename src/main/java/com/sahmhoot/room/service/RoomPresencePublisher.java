package com.sahmhoot.room.service;

import com.sahmhoot.quiz.service.port.RoomLockPort;
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
  private final RoomLockPort locks;
  private final SimpMessagingTemplate messaging;
  private final Clock clock;
  private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void changed(RoomPresenceChangedEvent event) {
    // #65가 내부 잠금을 잡고 동기 알림을 보낼 수 있다. 여기서는 방 잠금·DB·#65 조회를 하지 않는다.
    try {
      pending.compute(event.current().roomId(), (roomId, existing) -> {
        Pending state = existing != null ? existing : new Pending(event.previous());
        state.dirty = true;
        if (state.future == null && !state.running) schedule(roomId, state);
        return state;
      });
    } catch (RuntimeException error) {
      // #65의 동기 이벤트를 중단시키면 다른 처리기와 모든 방의 연결 추적에 영향을 준다.
      log.error("Room presence scheduling failed: roomId={}", event.current().roomId(), error);
    }
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
      boolean retry = false;
      try {
        // compute를 벗어난 뒤 #65를 조회한다. #65 이벤트 콜백과의 잠금 역전을 피한다.
        // 종료와 발행 순서를 지키기 위해 공유 방 잠금은 발행까지 유지한다.
        var change = presence.countChange(expected.previous, expected.lastCount);
        if (change.isEmpty()) { cancelAll(roomId); return; }
        if (expected.lastCount == null) expected.lastCount = change.get().previous();
        int count = change.get().current();
        if (expected.lastCount != count || expected.forcePublish) {
          // 예외가 나도 일부 전송됐을 수 있으므로, 재시도에서는 최신 전체 값을 다시 보낸다.
          expected.forcePublish = true;
          messaging.convertAndSend("/topic/rooms/" + roomId + "/presence",
              OnlineCountMessage.of(clock.instant(), count));
          expected.forcePublish = false;
        }
        expected.lastCount = count;
      } catch (RuntimeException error) {
        retry = true;
        log.error("Room presence publication failed: roomId={}", roomId, error);
      } finally {
        boolean retryRequired = retry;
        pending.computeIfPresent(roomId, (id, state) -> {
          if (state != expected) return state;
          state.running = false;
          if (!state.dirty && !retryRequired) return null;
          // 실패와 발행 도중 생긴 변경은 작업이 끝난 뒤부터 다시 1초를 기다린다.
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
    boolean forcePublish;
    Pending(RoomPresenceSnapshot previous) { this.previous = previous; }
  }
}
