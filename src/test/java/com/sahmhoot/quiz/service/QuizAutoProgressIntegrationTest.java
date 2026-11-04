package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

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
import com.sahmhoot.quiz.dto.message.QuestionOpenedMessage;
import com.sahmhoot.quiz.dto.message.QuizFinishedMessage;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.schedule.ManualQuizTaskScheduler;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * #23 완료 기준 통합 테스트 (실제 DB).
 *
 * <p>진짜 서비스·트랜잭션·방 잠금·DB를 그대로 쓰고, 시간만 가짜로 바꾼다.
 * <ul>
 *   <li>{@link MutableClock}: 퀴즈 서비스가 읽는 "지금"을 테스트가 손으로 넘긴다.</li>
 *   <li>{@link ManualQuizTaskScheduler}: 예약을 적어만 두고, {@code runNext()}를 부를 때 가장 이른 예약을
 *       그 시각으로 시계를 옮겨 실행한다. 그래서 20초짜리 문항도 기다리지 않는다.</li>
 * </ul>
 * 두 가짜는 {@code @Primary}로 등록되어, 이 테스트 안에서만 진짜 시계·예약 도구 대신 쓰인다.
 * DB가 시각을 초 단위로 저장하므로 시계는 정수 초로 둔다. 초 미만 시각을 확인하는 테스트만 예외다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizAutoProgressIntegrationTest {

  /** 퀴즈 시작 시각. 아래 기대 시각은 모두 여기서부터 센다. */
  private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");

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

  @Autowired MutableClock clock;
  @Autowired ManualQuizTaskScheduler scheduler;
  @Autowired UserRepository users;
  @Autowired RoomService roomService;
  @Autowired RoomManagementService roomManagement;
  @Autowired QuestionSetService setService;
  @Autowired QuestionService questionService;
  @Autowired QuizCoreService quizCoreService;
  @Autowired AnswerService answerService;
  @Autowired RunQuestionRepository runQuestions;
  @Autowired RunChoiceRepository runChoices;
  @Autowired JdbcTemplate jdbc;
  /** 학생 화면에 보내는 메시지를 가로채 순서를 확인한다. */
  @MockitoBean SimpMessagingTemplate messaging;

  private User professor;
  private User student;
  private Long roomId;

  @BeforeEach void setup() {
    clock.setTo(T0);
    scheduler.reset();
    professor = user(Role.PROFESSOR, "김교수");
    student = user(Role.STUDENT, "이학생");
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
    users.deleteById(student.getId());
  }

  @Test
  @DisplayName("3문항 세트가 사람 조작 없이 결과(FINISHED)까지 진행되고, 각 문항은 제한시간 + 1초에 한 번 닫힌다")
  void threeQuestionsProgressToResultWithoutHumanAction() {
    StartQuizResponse started = startQuiz(10, 20, 30);

    // 예약만 차례대로 실행한다. 사람이 부르는 것은 시작뿐이다.
    scheduler.runAll();

    List<RunQuestion> questions = runQuestions.findByQuizRunIdOrderByOrderNoAsc(started.runId());
    assertThat(questions).allMatch(RunQuestion::isClosed);
    // 1번 0초 열림 → 11초 닫힘 → 14초 2번 열림 → 35초 닫힘 → 38초 3번 열림 → 69초 닫힘 → 72초 결과
    assertThat(questions).extracting(RunQuestion::getClosesAt)
        .containsExactly(T0.plusSeconds(10), T0.plusSeconds(34), T0.plusSeconds(68));
    assertThat(questions).extracting(RunQuestion::getClosedAt)
        .containsExactly(T0.plusSeconds(11), T0.plusSeconds(35), T0.plusSeconds(69));
    assertThat(runStatus(started.runId())).isEqualTo("FINISHED");
    assertThat(clock.instant()).isEqualTo(T0.plusSeconds(72));

    // 마감 3번 + 다음 문항 열기 2번 + 결과 1번. 마감이 두 번 실행되거나 빠진 예약이 없다.
    assertThat(scheduler.executedCount()).isEqualTo(6);
    assertThat(sentMessageTypes()).containsExactly(
        QuestionOpenedMessage.class, QuestionClosedMessage.class,
        QuestionOpenedMessage.class, QuestionClosedMessage.class,
        QuestionOpenedMessage.class, QuestionClosedMessage.class,
        QuizFinishedMessage.class);
  }

  @Test
  @DisplayName("전원 응답으로 먼저 닫힌 문항의 원래 마감 예약은 늦게 실행돼도 아무것도 하지 않는다")
  void lateCloseForAlreadyClosedQuestionDoesNothing() {
    StartQuizResponse started = startQuiz(10, 20);
    Long first = started.firstRunQuestionId();

    // 5초에 조기 마감(전원 응답 시 AnswerService가 부르는 것과 같은 메서드)
    clock.setTo(T0.plusSeconds(5));
    quizCoreService.closeQuestion(roomId, first);
    // 남은 예약: 8초 2번 열기, 11초 1번의 원래 마감
    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(8), T0.plusSeconds(11));

    scheduler.runNext();   // 8초: 2번 열림, 29초 마감 예약
    scheduler.runNext();   // 11초: 1번의 늦은 마감 예약 → 이미 닫혀 무시

    List<RunQuestion> questions = runQuestions.findByQuizRunIdOrderByOrderNoAsc(started.runId());
    assertThat(questions.get(0).getClosedAt()).isEqualTo(T0.plusSeconds(5));
    assertThat(questions.get(1).getStatus()).isEqualTo(RunQuestionStatus.OPEN);
    // 늦은 예약이 다음 진행을 또 걸지 않았다: 남은 것은 2번의 마감 하나뿐
    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(29));
  }

  @Test
  @DisplayName("closes_at + 1초가 지난 답은 마감 예약이 아직 실행되지 않았어도 QUESTION_CLOSED로 거부된다")
  void answerAfterDeadlineIsRejected() {
    StartQuizResponse started = startQuiz(10);
    Long participantId = joinAsStudent();
    Long choiceId = runChoices.findByRunQuestionIdOrderByOrderNoAsc(started.firstRunQuestionId()).getFirst().getId();

    // 마감 10초 + 허용 1초를 넘긴 12초. 마감 예약(11초)은 일부러 실행하지 않는다.
    clock.setTo(T0.plusSeconds(12));

    assertThatThrownBy(() -> answerService.submitAnswer(roomId, participantId,
        new SubmitAnswerRequest(started.firstRunQuestionId(), choiceId), Set.of(participantId)))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode())
        .isEqualTo(ErrorCode.QUESTION_CLOSED);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answers WHERE run_question_id = ?",
        Integer.class, started.firstRunQuestionId())).isZero();
  }

  @Test
  @DisplayName("퀴즈를 중단하면 그 방의 예약이 모두 취소되어 이후 실행되는 예약이 0건이다")
  void abortCancelsAllRoomTasks() {
    StartQuizResponse started = startQuiz(10, 20);
    assertThat(scheduler.pendingCount(roomId)).isEqualTo(1);

    clock.setTo(T0.plusSeconds(3));
    quizCoreService.abortQuiz(roomId, started.runId(), professor.getId());

    assertThat(scheduler.pendingCount(roomId)).isZero();
    assertThat(scheduler.executedCount()).isZero();
    assertThat(runStatus(started.runId())).isEqualTo("ABORTED");
  }

  @Test
  @DisplayName("수업을 종료하면 방 모듈의 취소 연결을 통해 그 방의 예약이 모두 취소된다")
  void roomCloseCancelsAllRoomTasks() {
    StartQuizResponse started = startQuiz(10, 20);
    assertThat(scheduler.pendingCount(roomId)).isEqualTo(1);

    roomManagement.close(roomId, professor.getId());

    assertThat(scheduler.pendingCount(roomId)).isZero();
    assertThat(scheduler.executedCount()).isZero();
    assertThat(runStatus(started.runId())).isEqualTo("ABORTED");
    assertThat(jdbc.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, roomId))
        .isEqualTo("CLOSED");
  }

  @Test
  @DisplayName("초 미만 시각에 시작해도 저장·예약·답 검증이 같은 마감 시각을 쓰고 피드백은 3초다")
  void fractionalStartKeepsDeadlineAndFeedbackConsistent() {
    // 10:00:00.200 + 10초 = 10:00:10.200 → 올림해서 11초가 마감, 12초가 허용 끝이다.
    clock.setTo(T0.plusMillis(200));
    StartQuizResponse started = startQuiz(10, 20);
    Long participantId = joinAsStudent();
    Long choiceId = runChoices.findByRunQuestionIdOrderByOrderNoAsc(started.firstRunQuestionId()).getFirst().getId();

    assertThat(started.closesAt()).isEqualTo(T0.plusSeconds(11));
    assertThat(runQuestions.findById(started.firstRunQuestionId()).orElseThrow().getClosesAt())
        .isEqualTo(T0.plusSeconds(11));
    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(12));

    // 허용 끝 직전의 답은 받는다. 온라인 참여자를 한 명 더 두어 전원 응답 조기 마감은 일어나지 않게 한다.
    clock.setTo(T0.plusMillis(11_900));
    answerService.submitAnswer(roomId, participantId,
        new SubmitAnswerRequest(started.firstRunQuestionId(), choiceId), Set.of(participantId, -1L));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answers WHERE run_question_id = ?",
        Integer.class, started.firstRunQuestionId())).isOne();

    scheduler.runNext();

    assertThat(clock.instant()).isEqualTo(T0.plusSeconds(12));
    assertThat(runQuestions.findById(started.firstRunQuestionId()).orElseThrow().getClosedAt())
        .isEqualTo(T0.plusSeconds(12));
    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(15));
  }

  @Test
  @DisplayName("마감 예약이 늦게 실행돼도 다음 문항은 실제 처리 3초 뒤에 열리고, 닫힌 시각은 closesAt + 1초로 기록된다")
  void delayedCloseKeepsThreeSecondFeedback() {
    StartQuizResponse started = startQuiz(10, 20);

    // 11초에 걸린 마감 예약이 16초에야 실행된 상황
    clock.setTo(T0.plusSeconds(16));
    scheduler.runNext();

    assertThat(runQuestions.findById(started.firstRunQuestionId()).orElseThrow().getClosedAt())
        .isEqualTo(T0.plusSeconds(11));
    assertThat(scheduler.pendingTimes()).containsExactly(T0.plusSeconds(19));

    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/rooms/" + roomId + "/quiz"), sent.capture());
    assertThat(sent.getAllValues()).filteredOn(QuestionClosedMessage.class::isInstance).singleElement()
        .isInstanceOfSatisfying(QuestionClosedMessage.class,
            message -> assertThat(message.nextOpensAt()).isEqualTo(T0.plusSeconds(19)));

    scheduler.runNext();

    assertThat(runQuestions.findByQuizRunIdOrderByOrderNoAsc(started.runId()).get(1).getOpenedAt())
        .isEqualTo(T0.plusSeconds(19));
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

  /** 학생을 방 참여자로 넣고 참여자 ID를 돌려준다. 학생 입장 서비스가 따로 없어 SQL로 넣는다. */
  private Long joinAsStudent() {
    jdbc.update("INSERT INTO room_participants (room_id, user_id, nickname, joined_at) VALUES (?, ?, ?, ?)",
        roomId, student.getId(), "익명1", Timestamp.from(Instant.now()));
    return jdbc.queryForObject("SELECT id FROM room_participants WHERE room_id = ? AND user_id = ?",
        Long.class, roomId, student.getId());
  }

  private User user(Role role, String name) {
    return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", name,
        role == Role.STUDENT ? UUID.randomUUID().toString().substring(0, 8) : null, role));
  }

  private String runStatus(Long runId) {
    return jdbc.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, runId);
  }

  /** 이 방의 퀴즈 채널로 보낸 메시지들의 종류(보낸 순서대로). */
  private List<Class<?>> sentMessageTypes() {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/rooms/" + roomId + "/quiz"), sent.capture());
    return sent.getAllValues().stream().<Class<?>>map(Object::getClass).toList();
  }
}
