package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.schedule.AnswerTaskScheduler;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AnswerPresenceListenerTest {
  private final AnswerTaskScheduler scheduler = mock(AnswerTaskScheduler.class);
  private final AnswerService answers = mock(AnswerService.class);
  private final Instant now = Instant.parse("2026-11-08T10:00:00Z");
  private final AnswerPresenceListener listener = new AnswerPresenceListener(scheduler, answers,
      Clock.fixed(now, ZoneOffset.UTC));

  @Test void disconnectCallbackOnlySchedulesAndDatabaseWorkRunsLater() {
    listener.onPresenceChanged(event(Set.of(2L, 3L), Set.of(2L)));
    var task = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(eq(now), task.capture());
    verifyNoInteractions(answers);
    task.getValue().run();
    verify(answers).closeIfAllAnswered(42L);
  }

  @Test void joiningStudentDoesNotTriggerCloseCheck() {
    listener.onPresenceChanged(event(Set.of(2L), Set.of(2L, 3L)));
    verifyNoInteractions(scheduler, answers);
  }

  @Test void failedSchedulingDoesNotBreakOtherPresenceListeners() {
    when(scheduler.schedule(any(), any())).thenThrow(new IllegalStateException("test scheduler failure"));
    assertThatCode(() -> listener.onPresenceChanged(event(Set.of(2L), Set.of()))).doesNotThrowAnyException();
  }

  private RoomPresenceChangedEvent event(Set<Long> before, Set<Long> after) {
    return new RoomPresenceChangedEvent(new RoomPresenceSnapshot(42L, before, false),
        new RoomPresenceSnapshot(42L, after, false), now);
  }
}
