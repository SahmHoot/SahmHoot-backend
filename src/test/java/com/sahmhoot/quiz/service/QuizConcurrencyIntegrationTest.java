package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.ChoiceRequest;
import com.sahmhoot.questionset.dto.CreateQuestionSetRequest;
import com.sahmhoot.questionset.dto.QuestionWriteRequest;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.service.QuestionService;
import com.sahmhoot.questionset.service.QuestionSetService;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.dto.message.QuestionClosedMessage;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.schedule.AnswerTaskScheduler;
import com.sahmhoot.quiz.schedule.ManualQuizTaskScheduler;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.service.RoomPresenceService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #32 퀴즈 동시성 통합 테스트 (실제 DB·트랜잭션·방 잠금, NFR-06·14).
 *
 * <p>여러 스레드를 {@link CountDownLatch}로 동시에 출발시켜, 실제 수업처럼 요청이 겹쳐도 한 방에 열린 문항은
 * 하나 이하이고 마감은 한 번만 일어나는지 확인한다. 시간과 예약은 {@link QuizAutoProgressIntegrationTest}와
 * 같은 가짜({@link MutableClock}, {@link ManualQuizTaskScheduler})를 써서 기다리지 않는다.
 * 서버 재시작 복구는 같은 파일의 #25 테스트와 배포 서버 수동 테스트로 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizConcurrencyIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-11-27T10:00:00Z");
  private static final int STUDENTS = 30;

  @TestConfiguration
  static class FakeTimeConfig {

    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock(T0);
    }

    @Bean
    @Primary
    ManualQuizTaskScheduler manualQuizTaskScheduler(MutableClock testClock) {
      return new ManualQuizTaskScheduler(testClock);
    }
  }

  @MockitoBean RoomPresenceService presence;
  @MockitoBean AnswerTaskScheduler answerTasks;
  /** 퀴즈 채널로 보낸 메시지를 가로채 발행 횟수를 센다. */
  @MockitoBean SimpMessagingTemplate messaging;

  @Autowired MutableClock clock;
  @Autowired ManualQuizTaskScheduler scheduler;
  @Autowired UserRepository users;
  @Autowired RoomService roomService;
  @Autowired QuestionSetService setService;
  @Autowired QuestionService questionService;
  @Autowired QuizCoreService quizCoreService;
  @Autowired AnswerService answerService;
  @Autowired RunChoiceRepository runChoices;
  @Autowired JdbcTemplate jdbc;

  private User professor;
  private final List<User> students = new ArrayList<>();
  private Long roomId;

  @BeforeEach void setup() {
    clock.setTo(T0);
    scheduler.reset();
    professor = user(Role.PROFESSOR, "김교수");
    roomId = roomService.create(professor.getId(), new CreateRoomRequest("자료구조")).id();
  }

  /** 이 테스트가 만든 교수·학생의 데이터만 자식 테이블부터 지운다. */
  @AfterEach void cleanup() {
    Long id = professor.getId();
    jdbc.update("DELETE FROM answers WHERE run_question_id IN (SELECT rq.id FROM run_questions rq "
        + "JOIN quiz_runs r ON rq.quiz_run_id = r.id JOIN rooms m ON r.room_id = m.id WHERE m.host_id = ?)", id);
    jdbc.update("DELETE FROM run_choices WHERE run_question_id IN (SELECT rq.id FROM run_questions rq "
        + "JOIN quiz_runs r ON rq.quiz_run_id = r.id JOIN rooms m ON r.room_id = m.id WHERE m.host_id = ?)", id);
    jdbc.update("DELETE FROM run_questions WHERE quiz_run_id IN (SELECT r.id FROM quiz_runs r "
        + "JOIN rooms m ON r.room_id = m.id WHERE m.host_id = ?)", id);
    jdbc.update("DELETE FROM quiz_runs WHERE room_id IN (SELECT id FROM rooms WHERE host_id = ?)", id);
    jdbc.update("DELETE FROM room_participants WHERE room_id IN (SELECT id FROM rooms WHERE host_id = ?)", id);
    jdbc.update("DELETE FROM rooms WHERE host_id = ?", id);
    jdbc.update("DELETE FROM choices WHERE question_id IN (SELECT q.id FROM questions q "
        + "JOIN question_sets s ON q.question_set_id = s.id WHERE s.host_id = ?)", id);
    jdbc.update("DELETE FROM questions WHERE question_set_id IN (SELECT id FROM question_sets WHERE host_id = ?)", id);
    jdbc.update("DELETE FROM question_sets WHERE host_id = ?", id);
    users.deleteById(id);
    students.forEach(student -> users.deleteById(student.getId()));
    students.clear();
  }

  @Test
  @DisplayName("접속한 학생 30명이 동시에 답하면 답은 30개, 전원 응답 마감은 한 번이고 다음 진행 예약도 하나다")
  void thirtyStudentsAnsweringAtOnceCloseTheQuestionOnce() throws Exception {
    var started = startQuiz(10, 20);
    List<Long> participants = joinStudents(STUDENTS);
    when(presence.getOnlineParticipantIds(roomId)).thenReturn(Set.copyOf(participants));
    Long questionId = started.firstRunQuestionId();
    Long choiceId = choiceIds(questionId).getFirst();
    clock.setTo(T0.plusSeconds(5));

    List<Throwable> errors = race(participants.stream().<Callable<Object>>map(participant ->
        () -> answerService.submitAnswer(roomId, participant, new SubmitAnswerRequest(questionId, choiceId))).toList());

    assertThat(errors).isEmpty();
    assertThat(answerService.countAnswered(questionId)).isEqualTo(STUDENTS);
    assertThat(questionStatus(questionId)).isEqualTo("CLOSED");
    assertThat(quizMessageCount(QuestionClosedMessage.class)).isEqualTo(1);
    assertThat(openQuestionCount(started.runId())).isZero();
    // 원래 마감 예약(T0+11)은 남아도 실행 때 아무것도 하지 않는다. 다음 문항 예약은 실제 마감 3초 뒤 하나뿐이다.
    assertThat(scheduler.pendingTimes()).containsExactlyInAnyOrder(T0.plusSeconds(11), T0.plusSeconds(8));
  }

  @Test
  @DisplayName("학생 30명이 각자 두 답을 동시에 보내도 오류 없이 학생마다 답이 하나만 남고 문항은 열려 있다")
  void concurrentAnswerChangesKeepOneAnswerPerStudent() throws Exception {
    var started = startQuiz(10);
    List<Long> participants = joinStudents(STUDENTS + 1);
    // 마지막 한 명은 접속만 하고 답하지 않아, 전원 응답 마감 없이 답 변경 경합만 확인한다.
    when(presence.getOnlineParticipantIds(roomId)).thenReturn(Set.copyOf(participants));
    Long questionId = started.firstRunQuestionId();
    List<Long> choices = choiceIds(questionId);
    clock.setTo(T0.plusSeconds(5));

    List<Callable<Object>> submits = new ArrayList<>();
    for (Long participant : participants.subList(0, STUDENTS)) {
      for (Long choice : choices) {
        submits.add(() -> answerService.submitAnswer(roomId, participant, new SubmitAnswerRequest(questionId, choice)));
      }
    }
    List<Throwable> errors = race(submits);

    assertThat(errors).isEmpty();
    assertThat(answerService.countAnswered(questionId)).isEqualTo(STUDENTS);
    assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT participant_id) FROM answers WHERE run_question_id = ?",
        Long.class, questionId)).isEqualTo(STUDENTS);
    assertThat(questionStatus(questionId)).isEqualTo("OPEN");
    assertThat(openQuestionCount(started.runId())).isEqualTo(1);
    assertThat(quizMessageCount(QuestionClosedMessage.class)).isZero();
  }

  /** 작업들을 동시에 출발시키고, 끝날 때까지 기다려 던진 예외만 모아 돌려준다. */
  private List<Throwable> race(List<Callable<Object>> tasks) throws InterruptedException {
    var start = new CountDownLatch(1);
    var errors = new ArrayList<Throwable>();
    try (var workers = Executors.newFixedThreadPool(tasks.size())) {
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<Object> task : tasks) {
        futures.add(workers.submit(() -> {
          if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
          return task.call();
        }));
      }
      start.countDown();
      for (Future<Object> future : futures) {
        try {
          future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
          errors.add(error.getCause());
        } catch (java.util.concurrent.TimeoutException error) {
          errors.add(error);
        }
      }
    }
    return errors;
  }

  /** 주어진 제한시간(초)의 객관식 문항들로 세트를 만들고 T0에 퀴즈를 시작한다. */
  private StartQuizResponse startQuiz(int... timeLimits) {
    Long setId = setService.create(new QuestionSetCurrentUser(professor.getId(), "PROFESSOR", professor.getName()),
        new CreateQuestionSetRequest("스택과 큐", null, Visibility.PRIVATE)).id();
    for (int i = 0; i < timeLimits.length; i++) {
      questionService.add(setId, professor.getId(), new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE,
          (i + 1) + "번 문제", timeLimits[i], List.of(new ChoiceRequest("정답", true), new ChoiceRequest("오답", false))));
    }
    return quizCoreService.startQuiz(roomId, new StartQuizRequest(setId), professor.getId());
  }

  /** 학생 계정을 만들어 방 참여자로 넣고 참여자 ID를 돌려준다. 입장 검증은 이 테스트 범위가 아니라 SQL로 넣는다. */
  private List<Long> joinStudents(int count) {
    List<Long> participantIds = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      User student = user(Role.STUDENT, "학생" + i);
      students.add(student);
      jdbc.update("INSERT INTO room_participants (room_id, user_id, nickname, joined_at) VALUES (?, ?, ?, ?)",
          roomId, student.getId(), "익명" + i, Timestamp.from(Instant.now()));
      participantIds.add(jdbc.queryForObject("SELECT id FROM room_participants WHERE room_id = ? AND user_id = ?",
          Long.class, roomId, student.getId()));
    }
    assertThat(new HashSet<>(participantIds)).hasSize(count);
    return participantIds;
  }

  private List<Long> choiceIds(Long runQuestionId) {
    return runChoices.findByRunQuestionIdOrderByOrderNoAsc(runQuestionId).stream().map(choice -> choice.getId()).toList();
  }

  private User user(Role role, String name) {
    return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", name,
        role == Role.STUDENT ? UUID.randomUUID().toString().substring(0, 8) : null, role));
  }

  private String questionStatus(Long runQuestionId) {
    return jdbc.queryForObject("SELECT status FROM run_questions WHERE id = ?", String.class, runQuestionId);
  }

  /** NFR-14: 한 회차에 동시에 열린 문항 수. 언제 확인해도 1 이하여야 한다. */
  private int openQuestionCount(Long runId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM run_questions WHERE quiz_run_id = ? AND status = 'OPEN'",
        Integer.class, runId);
  }

  /** 이 방의 퀴즈 채널로 보낸 메시지 중 주어진 종류의 개수. */
  private long quizMessageCount(Class<?> type) {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging, atLeast(0)).convertAndSend(eq("/topic/rooms/" + roomId + "/quiz"), sent.capture());
    return sent.getAllValues().stream().filter(type::isInstance).count();
  }
}
