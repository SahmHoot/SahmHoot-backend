package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.ChoiceRequest;
import com.sahmhoot.questionset.dto.CreateQuestionSetRequest;
import com.sahmhoot.questionset.dto.QuestionResponse;
import com.sahmhoot.questionset.dto.QuestionWriteRequest;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.service.QuestionService;
import com.sahmhoot.questionset.service.QuestionSetService;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * #22 완료 기준 통합 테스트 (실제 DB).
 *
 * <p>기존 퀴즈 테스트는 방·세트·문항을 SQL로 직접 넣고 시작하지만, 이 테스트는 방 생성·세트 생성·문항 추가
 * 서비스를 실제로 불러 데이터를 만든다. 그래서 퀴즈 → 포트 → 방·세트 모듈 구현(QuizRoomAdapter,
 * QuizQuestionSetAdapter)이 실제로 이어져 동작하는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class QuizPortIntegrationTest {
  @Autowired UserRepository users;
  @Autowired RoomService roomService;
  @Autowired RoomManagementService roomManagement;
  @Autowired QuestionSetService setService;
  @Autowired QuestionService questionService;
  @Autowired QuizCoreService quizCoreService;
  @Autowired RunQuestionRepository runQuestions;
  @Autowired RunChoiceRepository runChoices;
  @Autowired JdbcTemplate jdbc;
  /** 실시간 메시지 발행은 이 테스트의 관심사가 아니라 가짜로 둔다. */
  @MockitoBean SimpMessagingTemplate messaging;
  /** 마감 예약은 이 테스트의 관심사가 아니다. 진짜 예약이 남아 정리된 DB에 늦게 실행되지 않도록 가짜로 둔다. */
  @MockitoBean QuizTaskScheduler taskScheduler;

  private User professor;
  private User otherProfessor;

  @BeforeEach void setup() {
    professor = professor("김교수");
    otherProfessor = professor("박교수");
  }

  /** 이 테스트가 만든 두 교수의 데이터만 자식 테이블부터 지운다. */
  @AfterEach void cleanup() {
    for (User host : List.of(professor, otherProfessor)) {
      Long id = host.getId();
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
    }
  }

  @Test
  @DisplayName("방 생성 → 세트·문항 생성 → 퀴즈 시작 시 문항·선택지·제한시간이 순서대로 복사된다")
  void copiesQuestionsCreatedThroughQuestionSetApi() {
    Long roomId = room(professor);
    Long setId = set(professor, Visibility.PRIVATE);
    question(setId, "LIFO 자료구조는?", 10, "스택", "큐", "힙", "트리");
    question(setId, "FIFO 자료구조는?", 45, "큐", "스택", "힙", "트리");
    question(setId, "해시 평균 탐색 시간은?", 120, "O(1)", "O(n)", "O(log n)", "O(n²)");

    StartQuizResponse started = quizCoreService.startQuiz(roomId, new StartQuizRequest(setId), professor.getId());

    // 문항 수·순서·내용·제한시간·유형이 원본과 같다.
    assertThat(started.totalQuestions()).isEqualTo(3);
    List<RunQuestion> copied = runQuestions.findByQuizRunIdOrderByOrderNoAsc(started.runId());
    assertThat(copied)
        .extracting(RunQuestion::getOrderNo, RunQuestion::getContent, RunQuestion::getTimeLimitSeconds,
            RunQuestion::getType)
        .containsExactly(
            tuple(1, "LIFO 자료구조는?", 10, com.sahmhoot.quiz.entity.QuestionType.MULTIPLE_CHOICE),
            tuple(2, "FIFO 자료구조는?", 45, com.sahmhoot.quiz.entity.QuestionType.MULTIPLE_CHOICE),
            tuple(3, "해시 평균 탐색 시간은?", 120, com.sahmhoot.quiz.entity.QuestionType.MULTIPLE_CHOICE));
    // 1번 문항만 열리고 나머지는 대기다.
    assertThat(copied).extracting(RunQuestion::getStatus)
        .containsExactly(RunQuestionStatus.OPEN, RunQuestionStatus.READY, RunQuestionStatus.READY);
    assertThat(started.firstRunQuestionId()).isEqualTo(copied.getFirst().getId());
    // 선택지 순서·내용·정답 여부도 그대로다.
    assertThat(runChoices.findByRunQuestionIdOrderByOrderNoAsc(copied.get(1).getId()))
        .extracting(RunChoice::getOrderNo, RunChoice::getContent, RunChoice::isCorrect)
        .containsExactly(tuple(1, "큐", true), tuple(2, "스택", false), tuple(3, "힙", false),
            tuple(4, "트리", false));
    // 방은 방 모듈의 상태 전환 규칙을 거쳐 진행 중이 된다.
    assertThat(jdbc.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, roomId))
        .isEqualTo("PLAYING");
  }

  @Test
  @DisplayName("퀴즈 시작 후 원본 문항을 고치거나 지워도 진행 중 회차의 문항은 그대로다")
  void runIsUnaffectedByLaterEditsToOriginal() {
    Long roomId = room(professor);
    Long setId = set(professor, Visibility.PRIVATE);
    QuestionResponse first = question(setId, "LIFO 자료구조는?", 10, "스택", "큐");
    QuestionResponse second = question(setId, "FIFO 자료구조는?", 20, "큐", "스택");
    StartQuizResponse started = quizCoreService.startQuiz(roomId, new StartQuizRequest(setId), professor.getId());

    // 시작 뒤 원본 1번 문항 내용·제한시간·선택지를 바꾸고, 2번 문항은 지운다.
    questionService.update(setId, first.id(), professor.getId(), new QuestionWriteRequest(
        QuestionType.MULTIPLE_CHOICE, "바뀐 문항", 300,
        List.of(new ChoiceRequest("바뀐 정답", true), new ChoiceRequest("바뀐 오답", false),
            new ChoiceRequest("추가 선택지", false))));
    questionService.delete(setId, second.id(), professor.getId());

    // 회차에는 시작 시점의 내용이 그대로 남아 있다.
    List<RunQuestion> copied = runQuestions.findByQuizRunIdOrderByOrderNoAsc(started.runId());
    assertThat(copied)
        .extracting(RunQuestion::getOrderNo, RunQuestion::getContent, RunQuestion::getTimeLimitSeconds)
        .containsExactly(tuple(1, "LIFO 자료구조는?", 10), tuple(2, "FIFO 자료구조는?", 20));
    assertThat(runChoices.findByRunQuestionIdOrderByOrderNoAsc(copied.getFirst().getId()))
        .extracting(RunChoice::getContent, RunChoice::isCorrect)
        .containsExactly(tuple("스택", true), tuple("큐", false));
  }

  @Test
  @DisplayName("다른 교수의 세트는 공개 세트여도 시작할 수 없고 회차도 만들지 않는다")
  void rejectsOtherProfessorsQuestionSet() {
    Long roomId = room(professor);
    Long othersSetId = set(otherProfessor, Visibility.PUBLIC);
    question(othersSetId, "LIFO 자료구조는?", 10, "스택", "큐");

    assertErrorCode(() -> quizCoreService.startQuiz(roomId, new StartQuizRequest(othersSetId), professor.getId()),
        ErrorCode.FORBIDDEN);
    assertNoRunAndRoomStatus(roomId, "OPEN");
  }

  @Test
  @DisplayName("종료된 방에서는 퀴즈를 시작할 수 없다")
  void rejectsClosedRoom() {
    Long roomId = room(professor);
    Long setId = set(professor, Visibility.PRIVATE);
    question(setId, "LIFO 자료구조는?", 10, "스택", "큐");
    roomManagement.close(roomId, professor.getId());

    assertErrorCode(() -> quizCoreService.startQuiz(roomId, new StartQuizRequest(setId), professor.getId()),
        ErrorCode.ROOM_CLOSED);
    assertNoRunAndRoomStatus(roomId, "CLOSED");
  }

  @Test
  @DisplayName("퀴즈 진행 중(PLAYING)인 방에서는 새 퀴즈를 시작할 수 없다")
  void rejectsPlayingRoom() {
    Long roomId = room(professor);
    Long setId = set(professor, Visibility.PRIVATE);
    question(setId, "LIFO 자료구조는?", 10, "스택", "큐");
    quizCoreService.startQuiz(roomId, new StartQuizRequest(setId), professor.getId());

    assertErrorCode(() -> quizCoreService.startQuiz(roomId, new StartQuizRequest(setId), professor.getId()),
        ErrorCode.QUIZ_ALREADY_RUNNING);
    // 처음 시작한 회차 하나만 남는다.
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_runs WHERE room_id = ?", Integer.class, roomId))
        .isOne();
  }

  @Test
  @DisplayName("남의 방에서는 퀴즈를 시작할 수 없다")
  void rejectsOtherProfessorsRoom() {
    Long othersRoomId = room(otherProfessor);
    Long setId = set(professor, Visibility.PRIVATE);
    question(setId, "LIFO 자료구조는?", 10, "스택", "큐");

    assertErrorCode(() -> quizCoreService.startQuiz(othersRoomId, new StartQuizRequest(setId), professor.getId()),
        ErrorCode.FORBIDDEN);
    assertNoRunAndRoomStatus(othersRoomId, "OPEN");
  }

  private User professor(String name) {
    return users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", name, null,
        Role.PROFESSOR));
  }

  /** 방 생성 서비스로 방을 만든다(소유자 자동 참여 포함). */
  private Long room(User host) {
    return roomService.create(host.getId(), new CreateRoomRequest("자료구조")).id();
  }

  /** 세트 생성 서비스로 세트를 만든다. */
  private Long set(User host, Visibility visibility) {
    return setService.create(new QuestionSetCurrentUser(host.getId(), "PROFESSOR", host.getName()),
        new CreateQuestionSetRequest("스택과 큐", null, visibility)).id();
  }

  /** 문항 추가 서비스로 객관식 문항을 만든다. 첫 번째 선택지를 정답으로 둔다. */
  private QuestionResponse question(Long setId, String content, int seconds, String... choices) {
    List<ChoiceRequest> requests = new ArrayList<>();
    for (int i = 0; i < choices.length; i++) requests.add(new ChoiceRequest(choices[i], i == 0));
    Long hostId = jdbc.queryForObject("SELECT host_id FROM question_sets WHERE id = ?", Long.class, setId);
    return questionService.add(setId, hostId,
        new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE, content, seconds, requests));
  }

  private void assertErrorCode(Runnable action, ErrorCode expected) {
    assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getErrorCode()).isEqualTo(expected);
  }

  /** 거부된 시작은 회차를 남기지 않고 방 상태도 바꾸지 않는다. */
  private void assertNoRunAndRoomStatus(Long roomId, String status) {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quiz_runs WHERE room_id = ?", Integer.class, roomId))
        .isZero();
    assertThat(jdbc.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, roomId))
        .isEqualTo(status);
  }
}
