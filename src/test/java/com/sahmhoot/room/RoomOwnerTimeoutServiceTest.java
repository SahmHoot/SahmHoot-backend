package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomOwnerTimeoutService;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class RoomOwnerTimeoutServiceTest {
  private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
  private final RoomPresenceTaskScheduler scheduler = mock(RoomPresenceTaskScheduler.class);
  private final RoomLockManager locks = new RoomLockManager();
  @SuppressWarnings("unchecked")
  private final ObjectProvider<RoomPresencePort> sources = mock(ObjectProvider.class);
  private final RoomPresencePort source = mock(RoomPresencePort.class);
  private final RoomRepository rooms = mock(RoomRepository.class);
  private final RoomManagementService management = mock(RoomManagementService.class);
  private final Clock clock = mock(Clock.class);
  private final AtomicReference<Instant> now = new AtomicReference<>(T0);
  private final AtomicReference<RoomPresenceSnapshot> current = new AtomicReference<>();
  private final List<Task> tasks = new ArrayList<>();
  private final RoomOwnerTimeoutService service = new RoomOwnerTimeoutService(
      scheduler, locks, sources, rooms, management, clock);
  private Room room;

  @BeforeEach void setup() {
    room = new Room(1L, "수업", "123456");
    ReflectionTestUtils.setField(room, "id", 42L);
    when(clock.instant()).thenAnswer(call -> now.get());
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    when(sources.getObject()).thenReturn(source);
    when(source.getPresence(42L)).thenAnswer(call -> current.get());
    when(scheduler.schedule(any(), any())).thenAnswer(call -> {
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
      return future;
    });
  }

  @Test void disconnectUsesOccurrenceTimeAndDuplicateDoesNotExtendThirtyMinutes() {
    now.set(T0.plusSeconds(60)); // 전달이 1분 늦어도 끊긴 시점부터 30분이다.
    change(true, false, T0);
    change(true, false, T0.plusSeconds(60));
    assertThat(tasks).singleElement().extracting(Task::at).isEqualTo(T0.plusSeconds(1800));
    verifyNoInteractions(rooms, source, management);
  }

  @Test void unrelatedStudentOrRemainingOwnerTabDoesNotScheduleClose() {
    var previous = new RoomPresenceSnapshot(42L, Set.of(1L, 2L), true);
    var changedStudents = new RoomPresenceSnapshot(42L, Set.of(1L), true);
    service.changed(new RoomPresenceChangedEvent(previous, changedStudents, T0));
    assertThat(tasks).isEmpty();
    verifyNoInteractions(rooms, source, management);
  }

  @Test void exactDeadlineClosesUsingRoomOwnerOnceWhileHoldingRoomLock() {
    change(true, false, T0);
    var timer = tasks.removeFirst();
    doAnswer(call -> {
      assertThat(locks.isLocked(42L)).isTrue();
      return null;
    }).when(management).close(42L, 1L);
    now.set(T0.plusSeconds(1800));
    timer.action().run();
    timer.action().run(); // 이미 끝난 예약이 다시 호출되어도 종료를 반복하지 않는다.
    verify(management).close(42L, 1L);
    assertThat(locks.isLocked(42L)).isFalse();
  }

  @Test void earlyCallbackIsRescheduledToOriginalDeadline() {
    change(true, false, T0);
    var timer = tasks.removeFirst();
    now.set(T0.plusSeconds(1799));
    timer.action().run();
    assertThat(tasks).singleElement().extracting(Task::at).isEqualTo(T0.plusSeconds(1800));
    verifyNoInteractions(rooms, source, management);
  }

  @Test void reconnectCancelsTimerAndLateCallbackCannotClose() {
    change(true, false, T0);
    var timer = tasks.removeFirst();
    now.set(T0.plusSeconds(1799));
    change(false, true, now.get());
    verify(timer.future()).cancel(false);
    now.set(T0.plusSeconds(1800));
    timer.action().run();
    verifyNoInteractions(rooms, source, management);
  }

  @Test void secondDisconnectGetsNewThirtyMinutePeriodAndOldCallbackCannotCancelIt() {
    change(true, false, T0);
    var old = tasks.removeFirst();
    change(false, true, T0.plusSeconds(600));
    change(true, false, T0.plusSeconds(1200));
    assertThat(tasks).singleElement().extracting(Task::at).isEqualTo(T0.plusSeconds(3000));
    now.set(T0.plusSeconds(1800));
    old.action().run();
    verifyNoInteractions(management);
    runNext();
    verify(management).close(42L, 1L);
  }

  @Test void latestPresencePreventsCloseEvenWhenReconnectEventHasNotArrived() {
    change(true, false, T0);
    current.set(snapshot(true));
    runNext();
    verifyNoInteractions(management);
    assertThat(tasks).isEmpty();
  }

  @Test void staleDisconnectEventCannotUndoNewerReconnect() {
    change(true, false, T0);
    var timer = tasks.removeFirst();
    change(false, true, T0.plusSeconds(600));
    service.changed(new RoomPresenceChangedEvent(snapshot(true), snapshot(false), T0.plusSeconds(300)));
    now.set(T0.plusSeconds(1800));
    timer.action().run();
    assertThat(tasks).isEmpty();
    verifyNoInteractions(management);
  }

  @Test void reconnectWhileCallbackWaitsForRoomLockPreventsClose() throws Exception {
    change(true, false, T0);
    var timer = tasks.removeFirst();
    now.set(T0.plusSeconds(1800));
    try (var pool = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1);
      var pending = locks.executeWithLock(42L, () -> {
        var future = pool.submit(() -> { started.countDown(); timer.action().run(); });
        try {
          assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(error);
        }
        change(false, true, T0.plusSeconds(1799));
        return future;
      });
      pending.get(5, TimeUnit.SECONDS);
    }
    verifyNoInteractions(management);
  }

  @Test void missingOrAlreadyClosedRoomDoesNotReadPresenceOrNotifyAgain() {
    change(true, false, T0);
    when(rooms.findById(42L)).thenReturn(Optional.empty());
    runNext();
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    room.close(T0);
    change(true, false, T0.plusSeconds(2000));
    runNext();
    verifyNoInteractions(source, management);
  }

  @Test void closeFailureRetriesWithoutResettingDisconnectDeadline() {
    change(true, false, T0);
    doThrow(new IllegalStateException("일시적 저장 실패")).doNothing().when(management).close(42L, 1L);
    runNext();
    assertThat(tasks).singleElement().extracting(Task::at).isEqualTo(T0.plusSeconds(1830));
    runNext();
    verify(management, times(2)).close(42L, 1L);
    assertThat(tasks).isEmpty();
  }

  @Test void inconsistentOwnerSnapshotNeverClosesRoom() {
    change(true, false, T0);
    current.set(new RoomPresenceSnapshot(42L, Set.of(1L), false));
    runNext();
    verifyNoInteractions(management);
    assertThat(tasks).hasSize(1);
  }

  private void change(boolean before, boolean after, Instant occurredAt) {
    current.set(snapshot(after));
    service.changed(new RoomPresenceChangedEvent(snapshot(before), current.get(), occurredAt));
  }

  private RoomPresenceSnapshot snapshot(boolean online) {
    return new RoomPresenceSnapshot(42L, online ? Set.of(1L, 2L) : Set.of(2L), online);
  }

  private void runNext() {
    var timer = tasks.removeFirst();
    now.set(timer.at());
    timer.action().run();
  }

  private record Task(Instant at, Runnable action, ScheduledFuture<?> future) {}
}
