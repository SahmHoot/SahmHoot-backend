package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.service.AnswerService;
import com.sahmhoot.quiz.service.QuizCoreService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomManagementIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomService creation;
  @Autowired RoomManagementService management;
  @Autowired RoomLockManager locks;
  @Autowired QuizCoreService quizzes;
  @Autowired AnswerService answers;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @MockitoSpyBean RoomRepository rooms;
  @MockitoBean SimpMessagingTemplate messaging;
  @MockitoBean(name = "firstCanceller", extraInterfaces = Ordered.class) RoomTaskCanceller canceller;
  @MockitoBean(name = "remainingCanceller", extraInterfaces = Ordered.class) RoomTaskCanceller remaining;
  private User professor;
  private User other;
  private User student;

  @BeforeEach void setup() {
    professor = user(Role.PROFESSOR);
    other = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    when(((Ordered) canceller).getOrder()).thenReturn(0);
    when(((Ordered) remaining).getOrder()).thenReturn(1);
  }

  @AfterEach void cleanup() {
    String roomIds = "SELECT id FROM rooms WHERE host_id IN (?, ?, ?)";
    String runIds = "SELECT id FROM quiz_runs WHERE room_id IN (" + roomIds + ")";
    String questionIds = "SELECT id FROM run_questions WHERE quiz_run_id IN (" + runIds + ")";
    Object[] owners = {professor.getId(), other.getId(), student.getId()};
    jdbc.update("DELETE FROM answers WHERE run_question_id IN (" + questionIds + ")", owners);
    jdbc.update("DELETE FROM run_choices WHERE run_question_id IN (" + questionIds + ")", owners);
    jdbc.update("DELETE FROM run_questions WHERE quiz_run_id IN (" + runIds + ")", owners);
    jdbc.update("DELETE FROM quiz_runs WHERE room_id IN (" + roomIds + ")", owners);
    jdbc.update("DELETE FROM room_participants WHERE room_id IN (" + roomIds + ")", owners);
    jdbc.update("DELETE FROM rooms WHERE host_id IN (?, ?, ?)", owners);
    jdbc.update("DELETE FROM question_sets WHERE host_id IN (?, ?, ?)", owners);
    for (User owner : new User[] {professor, other, student}) users.deleteById(owner.getId());
  }

  private User user(Role role) {
    return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only",
        "테스트", null, role));
  }
  private String token(User user) { return "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken(); }
  private long room(User owner) { return creation.create(owner.getId(), new CreateRoomRequest("수업")).id(); }
  private String state(long roomId) {
    return jdbc.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, roomId);
  }
  private ResultActions close(long roomId, User actor) throws Exception {
    return mvc.perform(patch("/api/rooms/" + roomId + "/close").header("Authorization", token(actor)));
  }

  @Test void mineContainsOnlyOwnOpenAndPlayingRoomsInStableOrder() throws Exception {
    long open = room(professor);
    long playing = room(professor);
    long closed = room(professor);
    room(other);
    jdbc.update("UPDATE rooms SET status = 'PLAYING' WHERE id = ?", playing);
    jdbc.update("UPDATE rooms SET status = 'CLOSED', closed_at = UTC_TIMESTAMP() WHERE id = ?", closed);
    jdbc.update("UPDATE rooms SET created_at = '2026-01-01 00:00:00' WHERE host_id = ?", professor.getId());
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(professor)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(playing)).andExpect(jsonPath("$[1].id").value(open))
        .andExpect(jsonPath("$[0].status").value("PLAYING"))
        .andExpect(jsonPath("$[1].status").value("OPEN"))
        .andExpect(jsonPath("$[0].onlineCount").value(0))
        .andExpect(jsonPath("$[0].joinCode").isString())
        .andExpect(jsonPath("$[0].createdAt").isString())
        .andExpect(jsonPath("$[0].length()").value(6));
  }

  @Test void emptyMineReturnsEmptyArrayAndStudentIsForbidden() throws Exception {
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(professor)))
        .andExpect(status().isOk()).andExpect(content().json("[]"));
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(student)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test void authenticationAndExistingUserAreRequired() throws Exception {
    mvc.perform(get("/api/rooms/mine")).andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/rooms/1/close")).andExpect(status().isUnauthorized());
    users.deleteById(professor.getId());
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(professor)))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test void mineRechecksDatabaseRole() throws Exception {
    jdbc.update("UPDATE users SET role = 'STUDENT' WHERE id = ?", professor.getId());
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(professor)))
        .andExpect(status().isForbidden());
  }

  @Test void nonOwnersCannotCloseEvenWhenTheyAreProfessors() throws Exception {
    long id = room(professor);
    for (User actor : new User[] {other, student}) close(id, actor)
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(state(id)).isEqualTo("OPEN");
    verifyNoInteractions(canceller, remaining, messaging);
  }

  @ParameterizedTest @ValueSource(strings = {"999999999", "-1", "0"})
  void absentRoomReturns404(String id) throws Exception {
    mvc.perform(patch("/api/rooms/" + id + "/close").header("Authorization", token(professor)))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    verifyNoInteractions(canceller, remaining, messaging);
  }

  @Test void malformedRoomIdReturnsValidationError() throws Exception {
    mvc.perform(patch("/api/rooms/abc/close").header("Authorization", token(professor)))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("roomId"));
  }

  @Test void closeCommitsBeforeCancellationAndNotificationWhileLockIsHeld() throws Exception {
    long id = room(professor);
    var cancellationLocked = new AtomicBoolean();
    var notificationLocked = new AtomicBoolean();
    var committed = new AtomicBoolean();
    var cancellationOutsideTransaction = new AtomicBoolean();
    var notificationOutsideTransaction = new AtomicBoolean();
    doAnswer(invocation -> {
      cancellationLocked.set(locks.isLocked(id));
      cancellationOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
      // A separate JDBC connection can see CLOSED only after the transaction committed.
      try (Connection connection = jdbc.getDataSource().getConnection();
           var statement = connection.prepareStatement("SELECT status FROM rooms WHERE id = ?")) {
        statement.setLong(1, id);
        try (var result = statement.executeQuery()) {
          committed.set(result.next() && "CLOSED".equals(result.getString(1)));
        }
      }
      return null;
    }).when(canceller).cancelAll(id);
    doAnswer(invocation -> {
      notificationLocked.set(locks.isLocked(id));
      notificationOutsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
      return null;
    })
        .when(messaging).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
    close(id, professor).andExpect(status().isNoContent()).andExpect(content().string(""));
    assertThat(cancellationLocked.get()).isTrue();
    assertThat(notificationLocked.get()).isTrue();
    assertThat(committed.get()).isTrue();
    assertThat(cancellationOutsideTransaction.get()).isTrue();
    assertThat(notificationOutsideTransaction.get()).isTrue();
    var message = ArgumentCaptor.forClass(RoomEndedMessage.class);
    var order = inOrder(canceller, remaining, messaging);
    order.verify(canceller).cancelAll(id);
    order.verify(remaining).cancelAll(id);
    order.verify(messaging).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), message.capture());
    assertThat(message.getValue().type()).isEqualTo("ROOM_ENDED");
    assertThat(message.getValue().sentAt()).isBeforeOrEqualTo(Instant.now());
    Instant closedAt = jdbc.queryForObject("SELECT closed_at FROM rooms WHERE id = ?",
        LocalDateTime.class, id).toInstant(ZoneOffset.UTC);
    assertThat(closedAt.getNano()).isZero();
    assertThat(locks.isLocked(id)).isFalse();
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(professor)))
        .andExpect(content().json("[]"));
  }

  @Test void repeatedClosePreservesTimestampAndPublishesOnce() throws Exception {
    long id = room(professor);
    close(id, professor).andExpect(status().isNoContent());
    LocalDateTime first = jdbc.queryForObject("SELECT closed_at FROM rooms WHERE id = ?", LocalDateTime.class, id);
    close(id, professor).andExpect(status().isNoContent());
    assertThat(jdbc.queryForObject("SELECT closed_at FROM rooms WHERE id = ?", LocalDateTime.class, id)).isEqualTo(first);
    close(id, other).andExpect(status().isForbidden());
    verify(canceller).cancelAll(id);
    verify(messaging).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
  }

  @Test void closeUsesOwnershipRatherThanAccountRole() throws Exception {
    long id = room(professor);
    jdbc.update("UPDATE users SET role = 'STUDENT' WHERE id = ?", professor.getId());
    close(id, professor).andExpect(status().isNoContent());
    assertThat(state(id)).isEqualTo("CLOSED");
  }

  private QuizFixture runningQuiz(long roomId) {
    jdbc.update("INSERT INTO question_sets(host_id,title,visibility,created_at,updated_at) VALUES (?, '기록 보존', 'PRIVATE', UTC_TIMESTAMP(), UTC_TIMESTAMP())", professor.getId());
    Long setId = jdbc.queryForObject("SELECT id FROM question_sets WHERE host_id = ?", Long.class, professor.getId());
    jdbc.update("INSERT INTO quiz_runs(room_id,question_set_id,status,started_at) VALUES (?, ?, 'RUNNING', UTC_TIMESTAMP())", roomId, setId);
    Long runId = jdbc.queryForObject("SELECT id FROM quiz_runs WHERE room_id = ?", Long.class, roomId);
    jdbc.update("INSERT INTO run_questions(quiz_run_id,type,content,time_limit_seconds,order_no,status,opened_at,closes_at) VALUES (?, 'TRUE_FALSE', '기록할 문항', 60, 1, 'OPEN', UTC_TIMESTAMP(), DATE_ADD(UTC_TIMESTAMP(), INTERVAL 60 SECOND))", runId);
    Long questionId = jdbc.queryForObject("SELECT id FROM run_questions WHERE quiz_run_id = ?", Long.class, runId);
    jdbc.update("INSERT INTO run_choices(run_question_id,content,order_no,is_correct) VALUES (?, 'O', 1, true)", questionId);
    Long choiceId = jdbc.queryForObject("SELECT id FROM run_choices WHERE run_question_id = ?", Long.class, questionId);
    jdbc.update("INSERT INTO room_participants(room_id,user_id,nickname,joined_at) VALUES (?, ?, '학생', UTC_TIMESTAMP())", roomId, student.getId());
    Long participantId = jdbc.queryForObject("SELECT id FROM room_participants WHERE room_id = ? AND user_id = ?", Long.class, roomId, student.getId());
    jdbc.update("INSERT INTO answers(run_question_id,participant_id,run_choice_id,submitted_at,updated_at) VALUES (?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())", questionId, participantId, choiceId);
    jdbc.update("UPDATE rooms SET status = 'PLAYING' WHERE id = ?", roomId);
    return new QuizFixture(runId, questionId, choiceId, participantId);
  }

  @Test void closingRunningQuizPreservesRecordsAndRejectsLateWork() throws Exception {
    long id = room(professor);
    QuizFixture quiz = runningQuiz(id);
    close(id, professor).andExpect(status().isNoContent());
    assertThat(state(id)).isEqualTo("CLOSED");
    assertThat(jdbc.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, quiz.runId())).isEqualTo("ABORTED");
    assertThat(jdbc.queryForObject("SELECT status FROM run_questions WHERE id = ?", String.class, quiz.questionId())).isEqualTo("CLOSED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answers WHERE run_question_id = ? AND run_choice_id = ?", Long.class, quiz.questionId(), quiz.choiceId())).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_participants WHERE room_id = ?", Long.class, id)).isEqualTo(2);
    assertThatThrownBy(() -> answers.submitAnswer(id, quiz.participantId(),
        new SubmitAnswerRequest(quiz.questionId(), quiz.choiceId()), Set.of())).isInstanceOf(BusinessException.class);
    quizzes.closeQuestion(id, quiz.questionId());
    quizzes.openQuestion(id, quiz.runId(), 2);
    quizzes.finishQuiz(id, quiz.runId());
    assertThat(state(id)).isEqualTo("CLOSED");
    verify(messaging).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
  }

  @Test void rollbackPreservesRunningQuizAndDoesNotCancelOrNotify() throws Exception {
    long id = room(professor);
    QuizFixture quiz = runningQuiz(id);
    doThrow(new DataIntegrityViolationException("Test room save failure")).when(rooms).saveAndFlush(any(Room.class));
    close(id, professor).andExpect(status().isInternalServerError());
    assertThat(state(id)).isEqualTo("PLAYING");
    assertThat(jdbc.queryForObject("SELECT closed_at FROM rooms WHERE id = ?", LocalDateTime.class, id)).isNull();
    assertThat(jdbc.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, quiz.runId())).isEqualTo("RUNNING");
    assertThat(jdbc.queryForObject("SELECT status FROM run_questions WHERE id = ?", String.class, quiz.questionId())).isEqualTo("OPEN");
    verifyNoInteractions(canceller, remaining, messaging);
  }

  @Test void concurrentCloseAndQuestionDeadlineLeaveRoomClosed() throws Exception {
    long id = room(professor);
    QuizFixture quiz = runningQuiz(id);
    together(() -> management.close(id, professor.getId()), () -> quizzes.closeQuestion(id, quiz.questionId()));
    assertThat(state(id)).isEqualTo("CLOSED");
    assertThat(jdbc.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, quiz.runId())).isEqualTo("ABORTED");
    verify(canceller).cancelAll(id);
  }

  @Test void simultaneousCloseOnlyPublishesOnce() throws Exception {
    long id = room(professor);
    together(() -> management.close(id, professor.getId()), () -> management.close(id, professor.getId()));
    assertThat(state(id)).isEqualTo("CLOSED");
    verify(canceller).cancelAll(id);
    verify(messaging).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
  }

  @Test void failedCancellationStillRunsRemainingHooksAndNotifies() throws Exception {
    long id = room(professor);
    doThrow(new IllegalStateException("Scheduler cancellation failed")).when(canceller).cancelAll(id);
    close(id, professor).andExpect(status().isNoContent());
    assertThat(state(id)).isEqualTo("CLOSED");
    var order = inOrder(canceller, remaining, messaging);
    order.verify(canceller).cancelAll(id);
    order.verify(remaining).cancelAll(id);
    order.verify(messaging).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
    verify(messaging, times(1)).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
  }

  @Test void notificationFailureIsLoggedAndCloseStillSucceeds() throws Exception {
    long id = room(professor);
    doThrow(new IllegalStateException("Broker unavailable")).when(messaging)
        .convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
    Logger logger = (Logger) LoggerFactory.getLogger(RoomManagementService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      close(id, professor).andExpect(status().isNoContent()).andExpect(content().string(""));
      assertThat(state(id)).isEqualTo("CLOSED");
      close(id, professor).andExpect(status().isNoContent()).andExpect(content().string(""));
      verify(canceller, times(1)).cancelAll(id);
      verify(remaining, times(1)).cancelAll(id);
      verify(messaging, times(1)).convertAndSend(eq("/topic/rooms/" + id + "/quiz"), any(RoomEndedMessage.class));
      assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.ERROR)
          .singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("Room ended notification failed: roomId=" + id);
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("Broker unavailable");
          });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test void cancellationCanCommitItsOwnRequiredTransactionEvenWithAnOuterCaller() {
    long id = room(professor);
    var outsideTransaction = new AtomicBoolean();
    doAnswer(invocation -> {
      outsideTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
      new TransactionTemplate(transactions).executeWithoutResult(tx ->
          jdbc.update("UPDATE rooms SET title = '취소 완료' WHERE id = ?", id));
      return null;
    }).when(canceller).cancelAll(id);
    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      management.close(id, professor.getId());
      tx.setRollbackOnly();
    });
    assertThat(outsideTransaction.get()).isTrue();
    assertThat(state(id)).isEqualTo("CLOSED");
    assertThat(jdbc.queryForObject("SELECT title FROM rooms WHERE id = ?", String.class, id)).isEqualTo("취소 완료");
  }

  private void together(Runnable first, Runnable second) throws Exception {
    var pool = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try {
      var tasks = Stream.of(first, second).map(task -> pool.submit(() -> {
        ready.countDown();
        try { if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        task.run();
      })).toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var task : tasks) task.get(10, TimeUnit.SECONDS);
    } finally { pool.shutdownNow(); }
  }

  private record QuizFixture(long runId, long questionId, long choiceId, long participantId) {}
}
