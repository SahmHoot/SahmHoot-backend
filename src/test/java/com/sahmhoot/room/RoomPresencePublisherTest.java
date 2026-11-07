package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.message.OnlineCountMessage;
import com.sahmhoot.room.service.RoomPresencePublisher;
import com.sahmhoot.room.service.RoomPresenceService;
import com.sahmhoot.room.service.RoomPresenceService.CountChange;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RoomPresencePublisherTest {
  private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
  private final RoomPresenceService presence = mock(RoomPresenceService.class);
  private final RoomPresenceTaskScheduler scheduler = mock(RoomPresenceTaskScheduler.class);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
  private final RoomLockManager locks = new RoomLockManager();
  private final Clock clock = mock(Clock.class);
  private final AtomicReference<Instant> now = new AtomicReference<>(T0);
  private final List<Task> tasks = new ArrayList<>();
  private final RoomPresencePublisher publisher = new RoomPresencePublisher(presence, scheduler, locks, messaging, clock);

  @BeforeEach void setup() {
    when(clock.instant()).thenAnswer(call -> now.get());
    when(scheduler.schedule(any(), any())).thenAnswer(call -> {
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
      return future;
    });
  }

  @Test void burstPublishesLatestFullCountOnceAfterOneSecond() {
    publisher.changed(event(Set.of(), Set.of(2L)));
    publisher.changed(event(Set.of(2L), Set.of(2L, 3L)));
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(0, 2)));
    assertThat(tasks).hasSize(1);
    assertThat(tasks.getFirst().at()).isEqualTo(T0.plusSeconds(1));
    verifyNoInteractions(messaging, presence);
    runNext();
    var sent = ArgumentCaptor.forClass(OnlineCountMessage.class);
    verify(messaging).convertAndSend(eq("/topic/rooms/42/presence"), sent.capture());
    assertThat(sent.getValue()).isEqualTo(OnlineCountMessage.of(T0.plusSeconds(1), 2));
    assertThat(tasks).isEmpty();
  }

  @Test void unchangedCountAndJoinLeaveBurstDoNotPublish() {
    publisher.changed(event(Set.of(), Set.of(2L)));
    publisher.changed(event(Set.of(2L), Set.of()));
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(0, 0)));
    runNext();
    verifyNoInteractions(messaging);
  }

  @Test void differentRoomsHaveIndependentPublicationWindows() {
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(0, 1)));
    publisher.changed(event(Set.of(), Set.of(2L)));
    publisher.changed(new RoomPresenceChangedEvent(new RoomPresenceSnapshot(43L, Set.of(), false),
        new RoomPresenceSnapshot(43L, Set.of(3L), false), T0));
    assertThat(tasks).hasSize(2);
    runNext();
    runNext();
    verify(messaging).convertAndSend(eq("/topic/rooms/42/presence"), any(OnlineCountMessage.class));
    verify(messaging).convertAndSend(eq("/topic/rooms/43/presence"), any(OnlineCountMessage.class));
  }

  @Test void changeDuringPublicationWaitsAnotherSecondAndSendsLatestValue() {
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(0, 1)), Optional.of(new CountChange(1, 2)));
    doAnswer(call -> {
      assertThat(locks.isLocked(42L)).isTrue();
      if (((OnlineCountMessage) call.getArgument(1)).onlineCount() == 1) {
        publisher.changed(event(Set.of(2L), Set.of(2L, 3L)));
      }
      return null;
    }).when(messaging).convertAndSend(anyString(), any(OnlineCountMessage.class));
    publisher.changed(event(Set.of(), Set.of(2L)));
    runNext();
    assertThat(tasks).singleElement().extracting(Task::at).isEqualTo(T0.plusSeconds(2));
    runNext();
    var sent = ArgumentCaptor.forClass(OnlineCountMessage.class);
    verify(messaging, times(2)).convertAndSend(eq("/topic/rooms/42/presence"), sent.capture());
    assertThat(sent.getAllValues()).extracting(OnlineCountMessage::onlineCount).containsExactly(1, 2);
    assertThat(sent.getAllValues()).extracting(OnlineCountMessage::sentAt)
        .containsExactly(T0.plusSeconds(1), T0.plusSeconds(2));
  }

  @Test void callbackAfterCancellationCannotPublishOrDeleteNewReservation() {
    publisher.changed(event(Set.of(), Set.of(2L)));
    var old = tasks.removeFirst();
    publisher.cancelAll(42L);
    verify(old.future()).cancel(false);
    publisher.changed(event(Set.of(), Set.of(3L)));
    old.action().run();
    verifyNoInteractions(presence, messaging);
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(0, 1)));
    runNext();
    verify(messaging).convertAndSend(anyString(), any(OnlineCountMessage.class));
  }

  @Test void closedRoomDropsReservationWithoutSending() {
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.empty());
    publisher.changed(event(Set.of(), Set.of(2L)));
    runNext();
    verifyNoInteractions(messaging);
    assertThat(tasks).isEmpty();
  }

  @Test void schedulerFailureDoesNotEscapeIntoSessionTrackerAndNextEventCanScheduleAgain() {
    doThrow(new java.util.concurrent.RejectedExecutionException("예약 실패"))
        .when(scheduler).schedule(any(), any());
    assertThatCode(() -> publisher.changed(event(Set.of(), Set.of(2L)))).doesNotThrowAnyException();
    verifyNoInteractions(presence, messaging);
    doAnswer(call -> {
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
      return future;
    }).when(scheduler).schedule(any(), any());
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(1, 2)));
    publisher.changed(event(Set.of(2L), Set.of(2L, 3L)));
    runNext();
    verify(messaging).convertAndSend(eq("/topic/rooms/42/presence"), any(OnlineCountMessage.class));
  }

  @Test void failedSendDoesNotPreventSubsequentChanges() {
    when(presence.countChange(any(), nullable(Integer.class))).thenReturn(Optional.of(new CountChange(0, 1)), Optional.of(new CountChange(1, 2)));
    doThrow(new IllegalStateException("전송 실패")).doNothing()
        .when(messaging).convertAndSend(anyString(), any(OnlineCountMessage.class));
    publisher.changed(event(Set.of(), Set.of(2L)));
    assertThatCode(this::runNext).doesNotThrowAnyException();
    publisher.changed(event(Set.of(2L), Set.of(2L, 3L)));
    runNext();
    verify(messaging, times(2)).convertAndSend(anyString(), any(OnlineCountMessage.class));
    assertThat(tasks).isEmpty();
  }

  @Test void failedSendRetriesLatestFullCountWithoutAnotherEvent() {
    when(presence.countChange(any(), nullable(Integer.class)))
        .thenReturn(Optional.of(new CountChange(0, 1)), Optional.of(new CountChange(0, 2)));
    doThrow(new IllegalStateException("전송 실패")).doNothing()
        .when(messaging).convertAndSend(anyString(), any(OnlineCountMessage.class));
    publisher.changed(event(Set.of(), Set.of(2L)));
    runNext();
    assertThat(tasks).singleElement().extracting(Task::at).isEqualTo(T0.plusSeconds(2));
    runNext(); // 새 이벤트 없이 포트의 최신 값을 읽는다.
    var sent = ArgumentCaptor.forClass(OnlineCountMessage.class);
    verify(messaging, times(2)).convertAndSend(eq("/topic/rooms/42/presence"), sent.capture());
    assertThat(sent.getAllValues()).extracting(OnlineCountMessage::onlineCount).containsExactly(1, 2);
    assertThat(sent.getAllValues()).extracting(OnlineCountMessage::sentAt)
        .containsExactly(T0.plusSeconds(1), T0.plusSeconds(2));
    verify(presence).countChange(any(), eq(0));
    assertThat(tasks).isEmpty();
  }

  @Test void readFailureRetriesAutomaticallyAndStopsAfterRecovery() {
    when(presence.countChange(any(), nullable(Integer.class)))
        .thenThrow(new IllegalStateException("조회 실패"))
        .thenReturn(Optional.of(new CountChange(0, 1)));
    publisher.changed(event(Set.of(), Set.of(2L)));
    runNext();
    verifyNoInteractions(messaging);
    assertThat(tasks).hasSize(1);
    runNext();
    verify(messaging).convertAndSend(eq("/topic/rooms/42/presence"), eq(OnlineCountMessage.of(T0.plusSeconds(2), 1)));
    assertThat(tasks).isEmpty();
  }

  @Test void failedAttemptMayHaveBeenDeliveredSoRetryAlsoPublishesWhenCountReturnsToBaseline() {
    when(presence.countChange(any(), nullable(Integer.class)))
        .thenReturn(Optional.of(new CountChange(0, 1)), Optional.of(new CountChange(0, 0)));
    doThrow(new IllegalStateException("전송 결과 불명")).doNothing()
        .when(messaging).convertAndSend(anyString(), any(OnlineCountMessage.class));
    publisher.changed(event(Set.of(), Set.of(2L)));
    runNext();
    runNext();
    verify(messaging).convertAndSend(eq("/topic/rooms/42/presence"), eq(OnlineCountMessage.of(T0.plusSeconds(2), 0)));
    assertThat(tasks).isEmpty();
  }

  @Test void cancellationAfterFailureStopsRetryAndLateCallbackCannotPublish() {
    when(presence.countChange(any(), nullable(Integer.class))).thenThrow(new IllegalStateException("조회 실패"));
    publisher.changed(event(Set.of(), Set.of(2L)));
    runNext();
    Task retry = tasks.removeFirst();
    publisher.cancelAll(42L);
    verify(retry.future()).cancel(false);
    retry.action().run();
    verify(presence, times(1)).countChange(any(), nullable(Integer.class));
    verifyNoInteractions(messaging);
  }

  private void runNext() {
    var task = tasks.removeFirst();
    now.set(task.at());
    task.action().run();
  }

  private RoomPresenceChangedEvent event(Set<Long> before, Set<Long> after) {
    return new RoomPresenceChangedEvent(new RoomPresenceSnapshot(42L, before, false),
        new RoomPresenceSnapshot(42L, after, false), now.get());
  }

  private record Task(Instant at, Runnable action, ScheduledFuture<?> future) {}
}
