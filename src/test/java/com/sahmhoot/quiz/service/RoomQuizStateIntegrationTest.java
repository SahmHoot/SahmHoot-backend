package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.service.QuestionService;
import com.sahmhoot.questionset.service.QuestionSetService;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.dto.message.QuestionClosedMessage;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.schedule.ManualQuizTaskScheduler;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.room.service.port.RoomQuizStatePort;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 실제 퀴즈 포트·MySQL·JWT·HTTP를 사용하며 시계와 예약 실행만 수동으로 제어한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomQuizStateIntegrationTest {
  private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");

  @TestConfiguration
  static class TimeConfig {
    @Bean @Primary MutableClock stateClock() { return new MutableClock(T0); }
    @Bean @Primary ManualQuizTaskScheduler stateScheduler(MutableClock clock) {
      return new ManualQuizTaskScheduler(clock);
    }
  }

  @Autowired MutableClock clock;
  @Autowired ManualQuizTaskScheduler scheduler;
  @Autowired MockMvc mvc;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomParticipantRepository participants;
  @Autowired RoomService rooms;
  @Autowired RoomManagementService management;
  @Autowired QuestionSetService sets;
  @Autowired QuestionService questions;
  @Autowired QuizCoreService quizzes;
  @Autowired QuizCoreTxService quizTransactions;
  @Autowired AnswerService answers;
  @Autowired RunChoiceRepository choices;
  @Autowired RunQuestionRepository runQuestions;
  @Autowired RoomQuizStatePort statePort;
  @Autowired QuizFeedbackState feedback;
  @Autowired RoomLockManager locks;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean SimpMessagingTemplate messaging;
  private User owner;
  private User student;
  private User other;
  private Long roomId;
  private Long setId;
  private Long participantId;
  private Long otherParticipantId;

  @BeforeEach void setup() {
    clock.setTo(T0);
    scheduler.reset();
    owner = user(Role.PROFESSOR, "교수");
    student = user(Role.STUDENT, "학생실명");
    other = user(Role.STUDENT, "다른학생");
    roomId = rooms.create(owner.getId(), new CreateRoomRequest("자료구조")).id();
    participantId = participants.saveAndFlush(new RoomParticipant(roomId, student.getId(), "익명1")).getId();
    otherParticipantId = participants.saveAndFlush(new RoomParticipant(roomId, other.getId(), "익명2")).getId();
    setId = sets.create(new QuestionSetCurrentUser(owner.getId(), "PROFESSOR", owner.getName()),
        new CreateQuestionSetRequest("스택과 큐", null, Visibility.PRIVATE)).id();
    for (int i = 1; i <= 2; i++) {
      questions.add(setId, owner.getId(), new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE,
          i + "번 문항", 10, List.of(new ChoiceRequest("정답", true), new ChoiceRequest("오답", false))));
    }
  }

  @AfterEach void cleanup() {
    management.close(roomId, owner.getId());
    scheduler.reset();
    jdbc.update("DELETE FROM answers WHERE run_question_id IN (SELECT rq.id FROM run_questions rq "
        + "JOIN quiz_runs r ON rq.quiz_run_id = r.id WHERE r.room_id = ?)", roomId);
    jdbc.update("DELETE FROM run_choices WHERE run_question_id IN (SELECT rq.id FROM run_questions rq "
        + "JOIN quiz_runs r ON rq.quiz_run_id = r.id WHERE r.room_id = ?)", roomId);
    jdbc.update("DELETE FROM run_questions WHERE quiz_run_id IN (SELECT id FROM quiz_runs WHERE room_id = ?)", roomId);
    jdbc.update("DELETE FROM quiz_runs WHERE room_id = ?", roomId);
    jdbc.update("DELETE FROM room_participants WHERE room_id = ?", roomId);
    jdbc.update("DELETE FROM rooms WHERE id = ?", roomId);
    jdbc.update("DELETE FROM choices WHERE question_id IN (SELECT id FROM questions WHERE question_set_id = ?)", setId);
    jdbc.update("DELETE FROM questions WHERE question_set_id = ?", setId);
    jdbc.update("DELETE FROM question_sets WHERE id = ?", setId);
    List.of(owner, student, other).forEach(user -> users.deleteById(user.getId()));
  }

  @Test void realQuizRestoresEachPhaseAndKeepsAnswersAndProgressPrivate() throws Exception {
    state(student).andExpect(jsonPath("$.quiz").value(nullValue()));
    var started = start();
    var currentChoices = choices.findByRunQuestionIdOrderByOrderNoAsc(started.firstRunQuestionId());
    Long correct = currentChoices.get(0).getId();
    Long wrong = currentChoices.get(1).getId();
    answers.submitAnswer(roomId, participantId, new SubmitAnswerRequest(started.firstRunQuestionId(), correct), Set.of());
    answers.submitAnswer(roomId, participantId, new SubmitAnswerRequest(started.firstRunQuestionId(), wrong), Set.of());
    answers.submitAnswer(roomId, otherParticipantId, new SubmitAnswerRequest(started.firstRunQuestionId(), correct), Set.of());
    state(student).andExpect(jsonPath("$.quiz.phase").value("QUESTION"))
        .andExpect(jsonPath("$.quiz.questionSetTitle").value("스택과 큐"))
        .andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(nullValue()))
        .andExpect(jsonPath("$.quiz.currentQuestion.choices[0].isCorrect").doesNotExist())
        .andExpect(jsonPath("$.quiz.result").value(nullValue()))
        .andExpect(jsonPath("$.quiz.myAnswers.length()").value(1))
        .andExpect(jsonPath("$.quiz.myAnswers[0].choiceId").value(wrong))
        .andExpect(jsonPath("$.progress").doesNotExist());
    state(other).andExpect(jsonPath("$.quiz.myAnswers[0].choiceId").value(correct));
    state(owner).andExpect(jsonPath("$.progress.answeredCount").value(2))
        .andExpect(jsonPath("$.quiz.myAnswers").doesNotExist())
        .andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(nullValue()));

    scheduler.runNext();
    state(student).andExpect(jsonPath("$.quiz.phase").value("FEEDBACK"))
        .andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(correct))
        .andExpect(jsonPath("$.quiz.nextOpensAt").value(T0.plusSeconds(14).toString()))
        .andExpect(jsonPath("$.quiz.result").value(nullValue()));
    state(owner).andExpect(jsonPath("$.progress").doesNotExist());
    scheduler.runNext();
    state(student).andExpect(jsonPath("$.quiz.phase").value("QUESTION"))
        .andExpect(jsonPath("$.quiz.currentQuestion.questionNo").value(2))
        .andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(nullValue()))
        .andExpect(jsonPath("$.quiz.myAnswers[0].choiceId").value(wrong));
    assertThat(feedback.nextOpensAt(roomId, started.runId(), started.firstRunQuestionId())).isEmpty();
    scheduler.runAll();
    state(student).andExpect(jsonPath("$.quiz.phase").value("RESULT"))
        .andExpect(jsonPath("$.quizRun.status").value("FINISHED"))
        .andExpect(jsonPath("$.quiz.result.questions[0].correctCount").value(1))
        .andExpect(jsonPath("$.quiz.result.questions[0].incorrectCount").value(1))
        .andExpect(jsonPath("$.quiz.result.questions[0].choices[0].count").value(1));
    quizzes.closeResult(roomId, owner.getId());
    state(student).andExpect(jsonPath("$.room.status").value("OPEN"))
        .andExpect(jsonPath("$.quiz").value(nullValue()));
    var nextRun = start();
    assertThat(nextRun.runId()).isNotEqualTo(started.runId());
    state(student).andExpect(jsonPath("$.quiz.myAnswers").isEmpty());
    quizzes.abortQuiz(roomId, nextRun.runId(), owner.getId());
    state(student).andExpect(jsonPath("$.quizRun.status").value("ABORTED"))
        .andExpect(jsonPath("$.quiz").value(nullValue()));
  }

  @Test void delayedCloseSnapshotUsesSameDeadlineAsEventAndScheduler() throws Exception {
    var started = start();
    clock.setTo(T0.plusSeconds(16));
    scheduler.runNext();
    assertThat(runQuestions.findById(started.firstRunQuestionId()).orElseThrow().getClosedAt())
        .isEqualTo(T0.plusSeconds(11));
    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(19));
    state(student).andExpect(jsonPath("$.serverTime").value(T0.plusSeconds(16).toString()))
        .andExpect(jsonPath("$.quiz.nextOpensAt").value(T0.plusSeconds(19).toString()));
    var sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/rooms/" + roomId + "/quiz"), sent.capture());
    assertThat(sent.getAllValues()).filteredOn(QuestionClosedMessage.class::isInstance).singleElement()
        .isInstanceOfSatisfying(QuestionClosedMessage.class,
            message -> assertThat(message.nextOpensAt()).isEqualTo(T0.plusSeconds(19)));
    management.close(roomId, owner.getId());
    assertThat(feedback.nextOpensAt(roomId, started.runId(), started.firstRunQuestionId())).isEmpty();
    assertThat(scheduler.pendingCount(roomId)).isZero();
    mvc.perform(get(path()).header("Authorization", token(student)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_CLOSED"));
  }

  @Test void rolledBackCloseNeverChangesSnapshotOrFeedbackDeadline() throws Exception {
    var started = start();
    clock.setTo(T0.plusSeconds(5));
    locks.executeWithLock(roomId, () -> new TransactionTemplate(transactions).execute(tx -> {
      quizTransactions.closeQuestion(roomId, started.firstRunQuestionId());
      assertThat(feedback.nextOpensAt(roomId, started.runId(), started.firstRunQuestionId())).isEmpty();
      tx.setRollbackOnly();
      return null;
    }));
    assertThat(feedback.nextOpensAt(roomId, started.runId(), started.firstRunQuestionId())).isEmpty();
    state(student).andExpect(jsonPath("$.quiz.phase").value("QUESTION"))
        .andExpect(jsonPath("$.quiz.currentQuestion.closedAt").value(nullValue()))
        .andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(nullValue()));
  }

  @Test void quizAdapterRequiresCallersTransaction() {
    assertThatThrownBy(() -> statePort.read(roomId, participantId))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test void realQuizStateRejectsUnauthenticatedNonparticipantAndLeftParticipant() throws Exception {
    start();
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    participants.deleteById(otherParticipantId);
    mvc.perform(get(path()).header("Authorization", token(other)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", participantId);
    mvc.perform(get(path()).header("Authorization", token(student)))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
    state(owner).andExpect(jsonPath("$.quiz.phase").value("QUESTION"));
  }

  private StartQuizResponse start() { return quizzes.startQuiz(roomId, new StartQuizRequest(setId), owner.getId()); }
  private String path() { return "/api/rooms/" + roomId + "/state"; }
  private String token(User user) { return "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken(); }
  private ResultActions state(User user) throws Exception {
    return mvc.perform(get(path()).header("Authorization", token(user))).andExpect(status().isOk());
  }
  private User user(Role role, String name) {
    return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", name, null, role));
  }
}
