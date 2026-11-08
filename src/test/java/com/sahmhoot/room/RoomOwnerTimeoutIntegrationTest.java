package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.*;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.service.QuestionService;
import com.sahmhoot.questionset.service.QuestionSetService;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.dto.message.QuizCanceledMessage;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import com.sahmhoot.quiz.service.AnswerService;
import com.sahmhoot.quiz.service.QuizCoreService;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.dto.message.RoomEndedMessage;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** #65 이벤트·시간·예약만 제어하고 실제 수업 종료 트랜잭션과 DB 기록을 검증한다. */
@SpringBootTest
@ActiveProfiles("test")
class RoomOwnerTimeoutIntegrationTest {
  private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

  @TestConfiguration
  static class TimeConfig {
    @Bean @Primary MutableClock timeoutClock() { return new MutableClock(T0); }
  }

  @Autowired MutableClock clock;
  @Autowired RoomService creation;
  @Autowired RoomManagementService management;
  @Autowired RoomParticipantRepository participants;
  @Autowired UserRepository users;
  @Autowired QuestionSetService sets;
  @Autowired QuestionService questions;
  @Autowired QuizCoreService quizzes;
  @Autowired AnswerService answers;
  @Autowired RunChoiceRepository choices;
  @Autowired ApplicationEventPublisher events;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean RoomPresencePort source;
  @MockitoBean RoomPresenceTaskScheduler scheduler;
  @MockitoBean QuizTaskScheduler quizScheduler;
  @MockitoBean SimpMessagingTemplate messaging;
  private final List<Task> tasks = new ArrayList<>();
  private final AtomicReference<RoomPresenceSnapshot> current = new AtomicReference<>();
  private User owner;
  private User student;
  private Long roomId;
  private Long participantId;
  private Long setId;

  @BeforeEach void setup() {
    clock.setTo(T0);
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    roomId = creation.create(owner.getId(), new CreateRoomRequest("수업")).id();
    participantId = participants.saveAndFlush(new RoomParticipant(roomId, student.getId(), "학생")).getId();
    current.set(snapshot(true));
    when(source.getPresence(roomId)).thenAnswer(call -> current.get());
    when(scheduler.schedule(any(), any())).thenAnswer(call -> {
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      var canceled = new AtomicBoolean();
      when(future.cancel(false)).thenAnswer(cancel -> { canceled.set(true); return true; });
      tasks.add(new Task(call.getArgument(0), call.getArgument(1), future, canceled));
      return future;
    });
  }

  @AfterEach void cleanup() {
    assertAll("자동 종료 DB 테스트 정리",
        () -> { if (roomId != null) management.close(roomId, owner.getId()); },
        () -> {
          if (roomId == null) return;
          assertAll(
              () -> jdbc.update("DELETE FROM answers WHERE run_question_id IN (SELECT rq.id FROM run_questions rq "
                  + "JOIN quiz_runs r ON rq.quiz_run_id = r.id WHERE r.room_id = ?)", roomId),
              () -> jdbc.update("DELETE FROM run_choices WHERE run_question_id IN (SELECT rq.id FROM run_questions rq "
                  + "JOIN quiz_runs r ON rq.quiz_run_id = r.id WHERE r.room_id = ?)", roomId),
              () -> jdbc.update("DELETE FROM run_questions WHERE quiz_run_id IN (SELECT id FROM quiz_runs WHERE room_id = ?)", roomId),
              () -> jdbc.update("DELETE FROM quiz_runs WHERE room_id = ?", roomId),
              () -> jdbc.update("DELETE FROM room_participants WHERE room_id = ?", roomId),
              () -> jdbc.update("DELETE FROM rooms WHERE id = ?", roomId));
        },
        () -> {
          if (setId == null) return;
          assertAll(
              () -> jdbc.update("DELETE FROM choices WHERE question_id IN (SELECT id FROM questions WHERE question_set_id = ?)", setId),
              () -> jdbc.update("DELETE FROM questions WHERE question_set_id = ?", setId),
              () -> jdbc.update("DELETE FROM question_sets WHERE id = ?", setId));
        },
        () -> { if (student != null) users.deleteById(student.getId()); },
        () -> { if (owner != null) users.deleteById(owner.getId()); });
  }

  @Test void thirtyMinuteTimeoutUsesNormalCloseAndPreservesQuizAndAnswerRecords() {
    setId = sets.create(new QuestionSetCurrentUser(owner.getId(), "PROFESSOR", owner.getName()),
        new CreateQuestionSetRequest("문제집", null, Visibility.PRIVATE)).id();
    questions.add(setId, owner.getId(), new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE, "문항", 10,
        List.of(new ChoiceRequest("정답", true), new ChoiceRequest("오답", false))));
    var quiz = quizzes.startQuiz(roomId, new StartQuizRequest(setId), owner.getId());
    var choice = choices.findByRunQuestionIdOrderByOrderNoAsc(quiz.firstRunQuestionId()).getFirst();
    answers.submitAnswer(roomId, participantId, new SubmitAnswerRequest(quiz.firstRunQuestionId(), choice.getId()));
    clearInvocations(messaging);
    changeOwner(false);
    advanceTo(T0.plusSeconds(1799));
    assertThat(status()).isEqualTo("PLAYING");
    doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(status()).isEqualTo("CLOSED");
      return null;
    }).when(messaging).convertAndSend(eq(topic()), any(RoomEndedMessage.class));
    advanceTo(T0.plusSeconds(1800));
    assertThat(status()).isEqualTo("CLOSED");
    assertThat(closedAt()).isEqualTo(T0.plusSeconds(1800));
    assertThat(jdbc.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, quiz.runId())).isEqualTo("ABORTED");
    assertThat(jdbc.queryForObject("SELECT status FROM run_questions WHERE id = ?", String.class, quiz.firstRunQuestionId()))
        .isEqualTo("CLOSED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_participants WHERE room_id = ?", Integer.class, roomId)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answers WHERE run_question_id = ?", Integer.class, quiz.firstRunQuestionId()))
        .isEqualTo(1);
    var sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging, times(2)).convertAndSend(eq(topic()), sent.capture());
    assertThat(sent.getAllValues().stream().map(Object::getClass).toList())
        .containsExactly(QuizCanceledMessage.class, RoomEndedMessage.class);
    assertThat(((RoomEndedMessage) sent.getAllValues().get(1)).sentAt()).isEqualTo(T0.plusSeconds(1800));
    verify(quizScheduler).cancelAll(roomId);
  }

  @Test void reconnectKeepsRoomOpenAndNextDisconnectStartsNewThirtyMinutes() {
    changeOwner(false);
    advanceTo(T0.plusSeconds(1740));
    changeOwner(true);
    advanceTo(T0.plusSeconds(2400));
    assertThat(status()).isEqualTo("OPEN");
    changeOwner(false);
    advanceTo(T0.plusSeconds(4199));
    assertThat(status()).isEqualTo("OPEN");
    advanceTo(T0.plusSeconds(4200));
    assertThat(status()).isEqualTo("CLOSED");
    assertThat(closedAt()).isEqualTo(T0.plusSeconds(4200));
    verify(messaging).convertAndSend(eq(topic()), any(RoomEndedMessage.class));
  }

  @Test void latestSourceConnectionPreventsCloseWhenReconnectEventIsDelayed() {
    changeOwner(false);
    advanceTo(T0.plusSeconds(1740));
    current.set(snapshot(true)); // 재연결 알림 전달 전이라도 현재 #65 상태는 접속이다.
    advanceTo(T0.plusSeconds(1800));
    assertThat(status()).isEqualTo("OPEN");
    verify(messaging, never()).convertAndSend(eq(topic()), any(RoomEndedMessage.class));
  }

  @Test void manualCloseCancelsTimerAndLateCallbackDoesNotNotifyAgain() {
    changeOwner(false);
    var timeout = timeoutTask();
    advanceTo(T0.plusSeconds(600));
    management.close(roomId, owner.getId());
    verify(timeout.future()).cancel(false);
    var savedClose = closedAt();
    clock.setTo(T0.plusSeconds(1800));
    timeout.action().run();
    assertThat(closedAt()).isEqualTo(savedClose);
    verify(messaging).convertAndSend(eq(topic()), any(RoomEndedMessage.class));
  }

  @Test void manualCloseAndAutomaticCloseRaceOnlyCommitsAndNotifiesOnce() throws Exception {
    changeOwner(false);
    var timeout = timeoutTask();
    advanceTo(T0.plusSeconds(1799));
    clock.setTo(T0.plusSeconds(1800));
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var automatic = pool.submit(() -> { start.await(); timeout.action().run(); return null; });
      var manual = pool.submit(() -> { start.await(); management.close(roomId, owner.getId()); return null; });
      start.countDown();
      automatic.get(10, TimeUnit.SECONDS);
      manual.get(10, TimeUnit.SECONDS);
    }
    assertThat(status()).isEqualTo("CLOSED");
    verify(messaging).convertAndSend(eq(topic()), any(RoomEndedMessage.class));
  }

  @Test void rolledBackDisconnectDoesNotScheduleButCommittedDisconnectDoes() {
    var before = current.get();
    current.set(snapshot(false));
    var change = new RoomPresenceChangedEvent(before, current.get(), T0);
    var tx = new TransactionTemplate(transactions);
    tx.execute(status -> {
      events.publishEvent(change);
      assertThat(tasks).isEmpty();
      status.setRollbackOnly();
      return null;
    });
    assertThat(tasks).isEmpty();
    tx.execute(status -> {
      events.publishEvent(change);
      assertThat(tasks).isEmpty();
      return null;
    });
    assertThat(timeoutTask().at()).isEqualTo(T0.plusSeconds(1800));
    advanceTo(T0.plusSeconds(1800));
    assertThat(status()).isEqualTo("CLOSED");
  }

  private void changeOwner(boolean online) {
    var previous = current.get();
    current.set(snapshot(online));
    events.publishEvent(new RoomPresenceChangedEvent(previous, current.get(), clock.instant()));
  }

  private RoomPresenceSnapshot snapshot(boolean online) {
    return new RoomPresenceSnapshot(roomId, online ? Set.of(owner.getId(), student.getId()) : Set.of(student.getId()), online);
  }

  private Task timeoutTask() {
    return tasks.stream().filter(task -> task.at().equals(T0.plusSeconds(1800))).findFirst().orElseThrow();
  }

  private void advanceTo(Instant at) {
    for (int attempt = 0; attempt < 100; attempt++) {
      var next = tasks.stream().filter(task -> !task.canceled().get() && !task.at().isAfter(at))
          .min(Comparator.comparing(Task::at));
      if (next.isEmpty()) { clock.setTo(at); return; }
      var task = next.get();
      tasks.remove(task);
      if (clock.instant().isBefore(task.at())) clock.setTo(task.at());
      task.action().run();
    }
    throw new IllegalStateException("예약 실행이 끝나지 않습니다.");
  }

  private String status() { return jdbc.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, roomId); }
  private Instant closedAt() {
    var value = jdbc.queryForObject("SELECT closed_at FROM rooms WHERE id = ?", LocalDateTime.class, roomId);
    return value == null ? null : value.toInstant(ZoneOffset.UTC);
  }
  private String topic() { return "/topic/rooms/" + roomId + "/quiz"; }
  private User user(Role role) {
    return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", "사용자", null, role));
  }
  private record Task(Instant at, Runnable action, ScheduledFuture<?> future, AtomicBoolean canceled) {}
}
