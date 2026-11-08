package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.message.AnswerProgressMessage;
import com.sahmhoot.quiz.dto.message.RoomWaitingMessage;
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.schedule.AnswerTaskScheduler;
import com.sahmhoot.quiz.schedule.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

class AnswerProgressPublisherTest {
  private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");
  private final MutableClock clock = new MutableClock(T0);
  private final RoomLockManager locks = new RoomLockManager();
  private final AnswerTaskScheduler scheduler = mock(AnswerTaskScheduler.class);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
  private final AnswerProgressPublisher publisher = new AnswerProgressPublisher(locks, scheduler, messaging, clock);
  private final List<Task> tasks = new ArrayList<>();

  @BeforeEach void setup() {
    when(scheduler.schedule(any(), any())).thenAnswer(call -> {
      var future = mock(ScheduledFuture.class);
      tasks.add(new Task(call.getArgument(0), call.getArgument(1), future));
      return future;
    });
  }

  @Test void firstResponseGoesOnlyToOwnerWithoutAnswersOrDistribution() {
    publish(42L, 812L, 1);
    var sent = ArgumentCaptor.forClass(AnswerProgressMessage.class);
    verify(messaging).convertAndSendToUser(eq("1"), eq("/queue/rooms/42/stats"), sent.capture());
    assertThat(new JsonMapper().writeValueAsString(sent.getValue())).isEqualTo(
        "{\"type\":\"ANSWER_PROGRESS\",\"sentAt\":\"2026-11-08T10:00:00Z\",\"runQuestionId\":812,\"answeredCount\":1}");
    verifyNoMoreInteractions(messaging);
  }

  @Test void burstCoalescesLatestCountAtHalfSecondIntervals() {
    publish(42L, 812L, 1);
    clock.setTo(T0.plusMillis(100));
    publish(42L, 812L, 2);
    clock.setTo(T0.plusMillis(200));
    publish(42L, 812L, 3);
    assertThat(tasks).hasSize(1);
    assertThat(tasks.getFirst().at()).isEqualTo(T0.plusMillis(500));
    runNext();
    var sent = ArgumentCaptor.forClass(AnswerProgressMessage.class);
    verify(messaging, times(2)).convertAndSendToUser(eq("1"), eq("/queue/rooms/42/stats"), sent.capture());
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::answeredCount).containsExactly(1, 3);
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::sentAt).containsExactly(T0, T0.plusMillis(500));
  }

  @Test void changedChoiceDoesNotAddCountOrRedundantMessage() {
    publish(42L, 812L, 1);
    clock.setTo(T0.plusSeconds(1));
    publish(42L, 812L, 1);
    verify(messaging, times(1)).convertAndSendToUser(anyString(), anyString(), any());
    assertThat(tasks).isEmpty();
  }

  @Test void transitionCancelsPendingMessageAndLateCallbackDoesNothing() {
    publish(42L, 812L, 1);
    publish(42L, 812L, 2);
    var pending = tasks.getFirst();
    publisher.onQuizChanged(new QuizStateChangedEvent(42L, RoomWaitingMessage.of(T0)));
    runNext();
    verify(pending.future()).cancel(false);
    verify(messaging, times(1)).convertAndSendToUser(anyString(), anyString(), any());
  }

  @Test void oldCallbackCannotOverwriteNewQuestion() {
    publish(42L, 812L, 1);
    publish(42L, 812L, 2);
    publisher.cancelAll(42L);
    clock.setTo(T0.plusSeconds(4));
    publish(42L, 813L, 1);
    runNext();
    var sent = ArgumentCaptor.forClass(AnswerProgressMessage.class);
    verify(messaging, times(2)).convertAndSendToUser(eq("1"), eq("/queue/rooms/42/stats"), sent.capture());
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::runQuestionId).containsExactly(812L, 813L);
  }

  @Test void differentRoomsHaveIndependentWindows() {
    publish(42L, 812L, 1);
    publish(43L, 912L, 1);
    verify(messaging).convertAndSendToUser(eq("1"), eq("/queue/rooms/42/stats"), any());
    verify(messaging).convertAndSendToUser(eq("1"), eq("/queue/rooms/43/stats"), any());
    assertThat(tasks).isEmpty();
  }

  @Test void rapidQuizRestartsDoNotResetRoomPublicationLimit() {
    publish(42L, 812L, 1);
    publisher.onQuizChanged(new QuizStateChangedEvent(42L, RoomWaitingMessage.of(T0)));
    clock.setTo(T0.plusMillis(100));
    publish(42L, 813L, 1);
    publisher.onQuizChanged(new QuizStateChangedEvent(42L, RoomWaitingMessage.of(clock.instant())));
    clock.setTo(T0.plusMillis(200));
    publish(42L, 814L, 1);

    verify(messaging, times(1)).convertAndSendToUser(anyString(), anyString(), any());
    runNext(); // 취소가 늦은 이전 회차 콜백
    runNext(); // 현재 회차의 최신 값
    var sent = ArgumentCaptor.forClass(AnswerProgressMessage.class);
    verify(messaging, times(2)).convertAndSendToUser(eq("1"), eq("/queue/rooms/42/stats"), sent.capture());
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::runQuestionId).containsExactly(812L, 814L);
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::sentAt).containsExactly(T0, T0.plusMillis(500));
  }

  @Test void sendFailureDoesNotBreakCommittedAnswerFlow() {
    doThrow(new IllegalStateException("test broker failure")).when(messaging)
        .convertAndSendToUser(anyString(), anyString(), any());
    assertThatCode(() -> publish(42L, 812L, 1)).doesNotThrowAnyException();
  }

  @Test void transientSendFailureRetriesWithoutAnotherAnswer() {
    doThrow(new IllegalStateException("test transient broker failure")).doNothing().when(messaging)
        .convertAndSendToUser(anyString(), anyString(), any());

    publish(42L, 812L, 1);

    assertThat(tasks).hasSize(1);
    assertThat(tasks.getFirst().at()).isEqualTo(T0.plusMillis(500));
    runNext();
    var sent = ArgumentCaptor.forClass(AnswerProgressMessage.class);
    verify(messaging, times(2)).convertAndSendToUser(eq("1"), eq("/queue/rooms/42/stats"), sent.capture());
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::answeredCount).containsExactly(1, 1);
    assertThat(sent.getAllValues()).extracting(AnswerProgressMessage::sentAt).containsExactly(T0, T0.plusMillis(500));
    assertThat(tasks).isEmpty();
  }

  @Test void earlyTimerExecutionNeverSendsInsideHalfSecond() {
    publish(42L, 812L, 1);
    publish(42L, 812L, 2);
    tasks.removeFirst().action().run();
    assertThat(tasks).hasSize(1);
    verify(messaging, times(1)).convertAndSendToUser(anyString(), anyString(), any());
    runNext();
    verify(messaging, times(2)).convertAndSendToUser(anyString(), anyString(), any());
  }

  private void publish(Long roomId, Long questionId, int count) {
    locks.executeWithLock(roomId, () -> publisher.publish(roomId, 1L, new AnswerProgressResponse(questionId, count)));
  }

  private void runNext() {
    var task = tasks.removeFirst();
    if (clock.instant().isBefore(task.at())) clock.setTo(task.at());
    task.action().run();
  }

  private record Task(Instant at, Runnable action, ScheduledFuture<?> future) {}
}
