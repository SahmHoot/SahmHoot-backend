package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import com.sahmhoot.quiz.service.port.QuestionSetSnapshot;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 DB(sahmhoot_test) 기반 퀴즈 전체 라이프사이클 및 변경 저장 통합 테스트.
 * - JdbcQuestionSetPort, JdbcRoomPort의 실제 SQL 동작 검증
 * - JPA - JDBC 간 동일 트랜잭션 공유 및 롤백 검증
 * - 퀴즈 시작 -> 문항 열림 -> 답안 제출/수정 -> 문항 마감 -> 퀴즈 종료 -> 결과 집계 -> 대기 복귀의 실제 DB 영속성 검증
 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
class QuizCoreRealDbIntegrationTest {

  private static final Long HOST_USER_ID = 100L;
  private static final Long STUDENT_1_USER_ID = 201L;
  private static final Long STUDENT_2_USER_ID = 202L;

  private static final Long ROOM_ID = 10L;
  private static final Long PARTICIPANT_1_ID = 501L;
  private static final Long PARTICIPANT_2_ID = 502L;

  private static final Long QUESTION_SET_ID = 30L;
  private static final Long EMPTY_QUESTION_SET_ID = 31L;

  private static final Long QUESTION_1_ID = 401L;
  private static final Long QUESTION_2_ID = 402L;

  private static final Long CHOICE_1_1_ID = 601L; // Q1 정답 (스택)
  private static final Long CHOICE_1_2_ID = 602L; // Q1 오답 (큐)
  private static final Long CHOICE_2_1_ID = 603L; // Q2 오답 (스택)
  private static final Long CHOICE_2_2_ID = 604L; // Q2 정답 (큐)

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private QuizCoreService quizCoreService;

  @Autowired
  private QuizCoreTxService quizCoreTxService;

  @Autowired
  private AnswerService answerService;

  @Autowired
  private QuizResultService quizResultService;

  @Autowired
  private RoomPort roomPort;

  @Autowired
  private QuestionSetPort questionSetPort;

  @Autowired
  private QuizRunRepository quizRunRepository;

  @Autowired
  private RunQuestionRepository runQuestionRepository;

  @Autowired
  private RunChoiceRepository runChoiceRepository;

  @Autowired
  private AnswerRepository answerRepository;

  @BeforeEach
  void setUpData() {
    cleanUpData();
    seedTestData();
  }

  @AfterEach
  void tearDown() {
    cleanUpData();
  }

  private void cleanUpData() {
    // 외래 키 역순으로 테스트 데이터 삭제
    jdbcTemplate.update("DELETE FROM answers WHERE participant_id IN (?, ?)", PARTICIPANT_1_ID, PARTICIPANT_2_ID);
    jdbcTemplate.update("DELETE FROM run_choices WHERE run_question_id IN (SELECT id FROM run_questions WHERE quiz_run_id IN (SELECT id FROM quiz_runs WHERE room_id = ?))", ROOM_ID);
    jdbcTemplate.update("DELETE FROM run_questions WHERE quiz_run_id IN (SELECT id FROM quiz_runs WHERE room_id = ?)", ROOM_ID);
    jdbcTemplate.update("DELETE FROM quiz_runs WHERE room_id = ?", ROOM_ID);
    jdbcTemplate.update("DELETE FROM room_participants WHERE room_id = ?", ROOM_ID);
    jdbcTemplate.update("DELETE FROM rooms WHERE id = ?", ROOM_ID);
    jdbcTemplate.update("DELETE FROM choices WHERE question_id IN (?, ?)", QUESTION_1_ID, QUESTION_2_ID);
    jdbcTemplate.update("DELETE FROM questions WHERE question_set_id IN (?, ?)", QUESTION_SET_ID, EMPTY_QUESTION_SET_ID);
    jdbcTemplate.update("DELETE FROM question_sets WHERE id IN (?, ?)", QUESTION_SET_ID, EMPTY_QUESTION_SET_ID);
    jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)", HOST_USER_ID, STUDENT_1_USER_ID, STUDENT_2_USER_ID);
  }

  private void seedTestData() {
    Timestamp now = Timestamp.from(Instant.now());

    // 1. users (host, student 1, student 2)
    jdbcTemplate.update(
        "INSERT INTO users (id, email, password_hash, name, student_number, role, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        HOST_USER_ID, "prof@sahmhoot.com", "hash1", "김교수", null, "PROFESSOR", true, now, now
    );
    jdbcTemplate.update(
        "INSERT INTO users (id, email, password_hash, name, student_number, role, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        STUDENT_1_USER_ID, "std1@sahmhoot.com", "hash2", "홍길동", "20260001", "STUDENT", true, now, now
    );
    jdbcTemplate.update(
        "INSERT INTO users (id, email, password_hash, name, student_number, role, email_verified, created_at, updated_at) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        STUDENT_2_USER_ID, "std2@sahmhoot.com", "hash3", "이순신", "20260002", "STUDENT", true, now, now
    );

    // 2. rooms (OPEN)
    jdbcTemplate.update(
        "INSERT INTO rooms (id, host_id, title, code, status, created_at) VALUES (?, ?, ?, ?, 'OPEN', ?)",
        ROOM_ID, HOST_USER_ID, "자료구조 수업방", "987654", now
    );

    // 3. room_participants
    jdbcTemplate.update(
        "INSERT INTO room_participants (id, room_id, user_id, nickname, joined_at) VALUES (?, ?, ?, ?, ?)",
        PARTICIPANT_1_ID, ROOM_ID, STUDENT_1_USER_ID, "용감한사자", now
    );
    jdbcTemplate.update(
        "INSERT INTO room_participants (id, room_id, user_id, nickname, joined_at) VALUES (?, ?, ?, ?, ?)",
        PARTICIPANT_2_ID, ROOM_ID, STUDENT_2_USER_ID, "빠른호랑이", now
    );

    // 4. question_sets (문제 있는 세트 30, 문항 없는 세트 31)
    jdbcTemplate.update(
        "INSERT INTO question_sets (id, host_id, title, description, visibility, created_at, updated_at) VALUES (?, ?, ?, ?, 'PUBLIC', ?, ?)",
        QUESTION_SET_ID, HOST_USER_ID, "스택과 큐 퀴즈", "자료구조 1주차", now, now
    );
    jdbcTemplate.update(
        "INSERT INTO question_sets (id, host_id, title, description, visibility, created_at, updated_at) VALUES (?, ?, ?, ?, 'PUBLIC', ?, ?)",
        EMPTY_QUESTION_SET_ID, HOST_USER_ID, "빈 세트", "문항 없음", now, now
    );

    // 5. questions (2문항)
    jdbcTemplate.update(
        "INSERT INTO questions (id, question_set_id, type, content, time_limit_seconds, order_no, created_at, updated_at) VALUES (?, ?, 'MULTIPLE_CHOICE', ?, ?, ?, ?, ?)",
        QUESTION_1_ID, QUESTION_SET_ID, "LIFO 특성을 갖는 자료구조는?", 10, 1, now, now
    );
    jdbcTemplate.update(
        "INSERT INTO questions (id, question_set_id, type, content, time_limit_seconds, order_no, created_at, updated_at) VALUES (?, ?, 'MULTIPLE_CHOICE', ?, ?, ?, ?, ?)",
        QUESTION_2_ID, QUESTION_SET_ID, "FIFO 특성을 갖는 자료구조는?", 10, 2, now, now
    );

    // 6. choices (각 2개 선택지)
    jdbcTemplate.update(
        "INSERT INTO choices (id, question_id, content, order_no, is_correct) VALUES (?, ?, '스택', 1, true)",
        CHOICE_1_1_ID, QUESTION_1_ID
    );
    jdbcTemplate.update(
        "INSERT INTO choices (id, question_id, content, order_no, is_correct) VALUES (?, ?, '큐', 2, false)",
        CHOICE_1_2_ID, QUESTION_1_ID
    );
    jdbcTemplate.update(
        "INSERT INTO choices (id, question_id, content, order_no, is_correct) VALUES (?, ?, '스택', 1, false)",
        CHOICE_2_1_ID, QUESTION_2_ID
    );
    jdbcTemplate.update(
        "INSERT INTO choices (id, question_id, content, order_no, is_correct) VALUES (?, ?, '큐', 2, true)",
        CHOICE_2_2_ID, QUESTION_2_ID
    );
  }

  @Test
  @DisplayName("실제 DB: 퀴즈 시작 -> 문항 열기 -> 답안 제출/수정 -> 문항 마감 -> 퀴즈 종료 -> 결과 집계 -> 대기 복귀 전체 흐름 검증")
  void fullQuizLifecycle_persistsCorrectlyInRealDb() {
    // 1. 퀴즈 시작 (startQuiz)
    StartQuizResponse startResponse = quizCoreService.startQuiz(
        ROOM_ID,
        new StartQuizRequest(QUESTION_SET_ID),
        HOST_USER_ID
    );

    Long runId = startResponse.runId();
    assertThat(runId).isNotNull();
    assertThat(startResponse.totalQuestions()).isEqualTo(2);

    // DB 검증: rooms.status = PLAYING
    String roomStatus = jdbcTemplate.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, ROOM_ID);
    assertThat(roomStatus).isEqualTo("PLAYING");

    // DB 검증: quiz_runs 상태 및 시작일시
    String runStatus = jdbcTemplate.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, runId);
    assertThat(runStatus).isEqualTo("RUNNING");

    // DB 검증: JdbcQuestionSetPort가 run_questions 및 run_choices에 스냅샷을 올바르게 복사했는지
    Integer copiedQuestionsCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM run_questions WHERE quiz_run_id = ?", Integer.class, runId);
    assertThat(copiedQuestionsCount).isEqualTo(2);

    Integer copiedChoicesCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM run_choices rc JOIN run_questions rq ON rc.run_question_id = rq.id WHERE rq.quiz_run_id = ?",
        Integer.class, runId);
    assertThat(copiedChoicesCount).isEqualTo(4);

    // 1번 문항은 OPEN 상태여야 함
    List<RunQuestion> questions = runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(runId);
    RunQuestion q1 = questions.get(0);
    RunQuestion q2 = questions.get(1);
    assertThat(q1.getStatus()).isEqualTo(RunQuestionStatus.OPEN);
    assertThat(q1.getOpenedAt()).isNotNull();
    assertThat(q2.getStatus()).isEqualTo(RunQuestionStatus.READY);

    // 1번 문항 선택지 조회 (스택=정답, 큐=오답)
    List<RunChoice> q1Choices = runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(q1.getId());
    RunChoice stackChoice = q1Choices.stream().filter(RunChoice::isCorrect).findFirst().orElseThrow();
    RunChoice queueChoice = q1Choices.stream().filter(c -> !c.isCorrect()).findFirst().orElseThrow();
    assertThat(stackChoice.getContent()).isEqualTo("스택");

    // 2. 답안 제출 (submitAnswer)
    // 참가자 1: 정답(스택) 제출 (접속 상태 연동 전 기본값: 조기 마감 없음)
    AnswerProgressResponse progress1 = answerService.submitAnswer(
        ROOM_ID,
        PARTICIPANT_1_ID,
        new SubmitAnswerRequest(q1.getId(), stackChoice.getId()),
        java.util.Set.of()
    );
    assertThat(progress1.answeredCount()).isEqualTo(1);

    // 참가자 2: 오답(큐) 제출
    AnswerProgressResponse progress2 = answerService.submitAnswer(
        ROOM_ID,
        PARTICIPANT_2_ID,
        new SubmitAnswerRequest(q1.getId(), queueChoice.getId()),
        java.util.Set.of()
    );
    assertThat(progress2.answeredCount()).isEqualTo(2);

    // 참가자 2: 마감 전 답변 변경 (큐 -> 스택)
    answerService.submitAnswer(
        ROOM_ID,
        PARTICIPANT_2_ID,
        new SubmitAnswerRequest(q1.getId(), stackChoice.getId()),
        java.util.Set.of()
    );

    // DB 검증: answers 테이블에 1인 1답안이 유지되고, 참가자 2의 선택지가 갱신되었는지
    Integer answerCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM answers WHERE run_question_id = ?", Integer.class, q1.getId());
    assertThat(answerCount).isEqualTo(2);

    Long p2ChoiceId = jdbcTemplate.queryForObject(
        "SELECT run_choice_id FROM answers WHERE run_question_id = ? AND participant_id = ?",
        Long.class, q1.getId(), PARTICIPANT_2_ID);
    assertThat(p2ChoiceId).isEqualTo(stackChoice.getId());

    // 3. 1번 문항 마감 (closeQuestion)
    CloseQuestionResult closeResult1 = quizCoreTxService.closeQuestion(ROOM_ID, q1.getId());
    assertThat(closeResult1.processed()).isTrue();
    assertThat(closeResult1.isLast()).isFalse();
    assertThat(closeResult1.nextOrderNo()).isEqualTo(2);
    assertThat(closeResult1.correctChoiceId()).isEqualTo(stackChoice.getId());

    // DB 검증: 1번 문항 status = CLOSED, closed_at 기록
    String q1DbStatus = jdbcTemplate.queryForObject("SELECT status FROM run_questions WHERE id = ?", String.class, q1.getId());
    assertThat(q1DbStatus).isEqualTo("CLOSED");

    // 회차는 3초 동안 RUNNING 유지
    String runStatusAfterQ1 = jdbcTemplate.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, runId);
    assertThat(runStatusAfterQ1).isEqualTo("RUNNING");

    // 4. 2번 문항 열기 (openQuestion) 및 답안 제출
    quizCoreTxService.openQuestion(ROOM_ID, runId, 2);
    String q2DbStatus = jdbcTemplate.queryForObject("SELECT status FROM run_questions WHERE id = ?", String.class, q2.getId());
    assertThat(q2DbStatus).isEqualTo("OPEN");

    List<RunChoice> q2Choices = runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(q2.getId());
    RunChoice q2Correct = q2Choices.stream().filter(RunChoice::isCorrect).findFirst().orElseThrow();
    RunChoice q2Incorrect = q2Choices.stream().filter(c -> !c.isCorrect()).findFirst().orElseThrow();

    // 참가자 1: 정답, 참가자 2: 오답 제출
    answerService.submitAnswer(ROOM_ID, PARTICIPANT_1_ID, new SubmitAnswerRequest(q2.getId(), q2Correct.getId()), java.util.Set.of());
    answerService.submitAnswer(ROOM_ID, PARTICIPANT_2_ID, new SubmitAnswerRequest(q2.getId(), q2Incorrect.getId()), java.util.Set.of());

    // 2번 문항 마감 (마지막 문항)
    CloseQuestionResult closeResult2 = quizCoreTxService.closeQuestion(ROOM_ID, q2.getId());
    assertThat(closeResult2.isLast()).isTrue();
    assertThat(closeResult2.nextOrderNo()).isNull();

    // 5. 퀴즈 종료 및 결과 집계 (finishQuiz)
    QuizResultResponse finalResult = quizCoreTxService.finishQuiz(ROOM_ID, runId);
    assertThat(finalResult).isNotNull();
    assertThat(finalResult.questions()).hasSize(2);

    // DB 검증: 회차 FINISHED, 방 상태는 결과 화면 동안 PLAYING 유지
    String finishedRunStatus = jdbcTemplate.queryForObject("SELECT status FROM quiz_runs WHERE id = ?", String.class, runId);
    assertThat(finishedRunStatus).isEqualTo("FINISHED");
    String roomStatusOnResult = jdbcTemplate.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, ROOM_ID);
    assertThat(roomStatusOnResult).isEqualTo("PLAYING");

    // 집계 내용 검증
    QuizResultResponse.QuestionResultDetail resQ1 = finalResult.questions().get(0);
    assertThat(resQ1.answeredCount()).isEqualTo(2);
    assertThat(resQ1.correctCount()).isEqualTo(2);
    assertThat(resQ1.incorrectCount()).isEqualTo(0);

    QuizResultResponse.QuestionResultDetail resQ2 = finalResult.questions().get(1);
    assertThat(resQ2.answeredCount()).isEqualTo(2);
    assertThat(resQ2.correctCount()).isEqualTo(1);
    assertThat(resQ2.incorrectCount()).isEqualTo(1);

    // 6. 결과 닫기 / 대기로 복귀 (closeResult)
    quizCoreService.closeResult(ROOM_ID, HOST_USER_ID);
    String roomStatusAfterClose = jdbcTemplate.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, ROOM_ID);
    assertThat(roomStatusAfterClose).isEqualTo("OPEN");
  }

  @Test
  @DisplayName("실제 DB: EMPTY_QUESTION_SET 발생 시 퀴즈 회차 생성 및 rooms.status=PLAYING 변경이 정상 롤백된다")
  void startQuiz_emptyQuestionSet_rollsBackRoomStatusAndRun() {
    // 빈 문제집(31)으로 퀴즈 시작 시도 -> EMPTY_QUESTION_SET 예외 발생
    assertThatThrownBy(() -> quizCoreTxService.startQuiz(
        ROOM_ID,
        new StartQuizRequest(EMPTY_QUESTION_SET_ID),
        HOST_USER_ID
    )).isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.EMPTY_QUESTION_SET);

    // DB 검증: 트랜잭션 롤백으로 rooms.status가 PLAYING으로 남지 않고 OPEN으로 유지되어야 함
    String roomStatus = jdbcTemplate.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, ROOM_ID);
    assertThat(roomStatus).isEqualTo("OPEN");

    // DB 검증: quiz_runs 레코드도 롤백되어 저장되지 않아야 함
    Integer runCount = jdbcTemplate.queryForObject("SELECT count(*) FROM quiz_runs WHERE room_id = ?", Integer.class, ROOM_ID);
    assertThat(runCount).isEqualTo(0);
  }

  @Test
  @DisplayName("실제 DB: JdbcRoomPort 및 JdbcQuestionSetPort 기본 CRUD 및 예외 처리 검증")
  void ports_directDbVerification() {
    // 1. JdbcRoomPort
    RoomSnapshot room = roomPort.getRoom(ROOM_ID);
    assertThat(room.id()).isEqualTo(ROOM_ID);
    assertThat(room.hostId()).isEqualTo(HOST_USER_ID);
    assertThat(room.status()).isEqualTo(RoomStatus.OPEN);

    // 참가자 ID 조회
    Long participantId = roomPort.findParticipantId(ROOM_ID, STUDENT_1_USER_ID);
    assertThat(participantId).isEqualTo(PARTICIPANT_1_ID);

    // 없는 참가자 예외
    assertThatThrownBy(() -> roomPort.findParticipantId(ROOM_ID, 9999L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.NOT_PARTICIPANT);

    // 없는 방 예외
    assertThatThrownBy(() -> roomPort.getRoom(9999L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.ROOM_NOT_FOUND);

    // 2. JdbcQuestionSetPort
    QuestionSetSnapshot qs = questionSetPort.getQuestionSet(QUESTION_SET_ID);
    assertThat(qs.id()).isEqualTo(QUESTION_SET_ID);
    assertThat(qs.hostId()).isEqualTo(HOST_USER_ID);

    // 없는 세트 예외
    assertThatThrownBy(() -> questionSetPort.getQuestionSet(9999L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.QUESTION_SET_NOT_FOUND);
  }

  @Test
  @DisplayName("실제 DB: 전원 응답 시(answeredCount >= onlineCount) 문항이 즉시 마감되고 DB에 반영된다")
  void submitAnswer_whenAllOnlineParticipantsAnswered_immediatelyClosesQuestionInRealDb() {
    // 1. 퀴즈 시작
    StartQuizResponse startResponse = quizCoreTxService.startQuiz(
        ROOM_ID,
        new StartQuizRequest(QUESTION_SET_ID),
        HOST_USER_ID
    );
    Long q1Id = startResponse.firstRunQuestionId();

    List<RunChoice> q1Choices = runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(q1Id);
    RunChoice stackChoice = q1Choices.stream().filter(RunChoice::isCorrect).findFirst().orElseThrow();
    RunChoice queueChoice = q1Choices.stream().filter(c -> !c.isCorrect()).findFirst().orElseThrow();

    // 2. 참가자 1 응답 (접속 중인 참가자 2명) -> 문항 여전히 OPEN
    AnswerProgressResponse progress1 = answerService.submitAnswer(
        ROOM_ID,
        PARTICIPANT_1_ID,
        new SubmitAnswerRequest(q1Id, stackChoice.getId()),
        java.util.Set.of(PARTICIPANT_1_ID, PARTICIPANT_2_ID)
    );
    assertThat(progress1.answeredCount()).isEqualTo(1);
    String statusAfterP1 = jdbcTemplate.queryForObject(
        "SELECT status FROM run_questions WHERE id = ?", String.class, q1Id);
    assertThat(statusAfterP1).isEqualTo("OPEN");

    // 3. 참가자 2 응답 -> 접속 중인 참가자 전원 응답으로 즉시 마감 트리거
    AnswerProgressResponse progress2 = answerService.submitAnswer(
        ROOM_ID,
        PARTICIPANT_2_ID,
        new SubmitAnswerRequest(q1Id, queueChoice.getId()),
        java.util.Set.of(PARTICIPANT_1_ID, PARTICIPANT_2_ID)
    );
    assertThat(progress2.answeredCount()).isEqualTo(2);

    // DB 검증: run_questions status가 CLOSED로 변경되고 closed_at이 기록됨
    String statusAfterP2 = jdbcTemplate.queryForObject(
        "SELECT status FROM run_questions WHERE id = ?", String.class, q1Id);
    assertThat(statusAfterP2).isEqualTo("CLOSED");
    Timestamp closedAt = jdbcTemplate.queryForObject(
        "SELECT closed_at FROM run_questions WHERE id = ?", Timestamp.class, q1Id);
    assertThat(closedAt).isNotNull();

    // 4. 마감 후 추가 제출 시도 시 QUESTION_CLOSED 예외 발생
    assertThatThrownBy(() -> answerService.submitAnswer(
        ROOM_ID,
        PARTICIPANT_2_ID,
        new SubmitAnswerRequest(q1Id, stackChoice.getId()),
        java.util.Set.of(PARTICIPANT_1_ID, PARTICIPANT_2_ID)
    )).isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.QUESTION_CLOSED);

    // 5. 이미 즉시 마감된 문항에 대해 중복 closeQuestion 호출 시 무시(ignored) 처리
    CloseQuestionResult duplicateClose = quizCoreTxService.closeQuestion(ROOM_ID, q1Id);
    assertThat(duplicateClose.processed()).isFalse();
  }
}
