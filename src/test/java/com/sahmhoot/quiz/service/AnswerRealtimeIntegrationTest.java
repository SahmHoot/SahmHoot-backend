package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.sahmhoot.auth.jwt.JwtProvider;
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
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JWT·STOMP·#45 접속자 조회·MySQL을 사용한다. 문항 제한시간 예약만 수동 제어한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AnswerRealtimeIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(7);
  @TestConfiguration
  static class RecorderConfig {
    @Bean CommittedEvents answerCommittedEvents() { return new CommittedEvents(); }
  }

  static class CommittedEvents {
    final List<QuizStateChangedEvent> values = new CopyOnWriteArrayList<>();
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void record(QuizStateChangedEvent event) { values.add(event); }
  }

  @LocalServerPort int port;
  @Autowired UserRepository users;
  @Autowired RoomService rooms;
  @Autowired RoomManagementService management;
  @Autowired RoomParticipantRepository participants;
  @Autowired QuestionSetService sets;
  @Autowired QuestionService questions;
  @Autowired QuizCoreService quizzes;
  @Autowired AnswerService answers;
  @Autowired RunChoiceRepository choices;
  @Autowired RunQuestionRepository runQuestions;
  @Autowired ParticipantPresencePort presence;
  @Autowired RoomPresencePort source;
  @Autowired JwtProvider jwt;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired CommittedEvents events;
  @MockitoBean QuizTaskScheduler quizScheduler;

  private final List<User> createdUsers = new ArrayList<>();
  private final List<Long> participantIds = new ArrayList<>();
  private final List<Connection> students = new ArrayList<>();
  private final List<WebSocketSession> sockets = new CopyOnWriteArrayList<>();
  private final BlockingQueue<JsonNode> ownerStats = new LinkedBlockingQueue<>();
  private final List<JsonNode> publicMessages = new CopyOnWriteArrayList<>();
  private final StandardWebSocketClient transport = new StandardWebSocketClient();
  private final WebSocketStompClient client = new WebSocketStompClient(new WebSocketClient() {
    @Override public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, String uri, Object... values) {
      return record(transport.execute(handler, uri, values));
    }
    @Override public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler, WebSocketHttpHeaders headers, URI uri) {
      return record(transport.execute(handler, headers, uri));
    }
    private CompletableFuture<WebSocketSession> record(CompletableFuture<WebSocketSession> result) {
      return result.thenApply(socket -> { sockets.add(socket); return socket; });
    }
  });
  private User owner;
  private Long roomId;
  private Long setId;
  private StartQuizResponse quiz;
  private Long correctChoice;
  private Long wrongChoice;

  @BeforeEach void setup() throws Exception {
    owner = user(Role.PROFESSOR);
    roomId = rooms.create(owner.getId(), new CreateRoomRequest("실시간 답안 검증")).id();
    var professor = connect(owner);
    professor.stomp().subscribe("/user/queue/rooms/" + roomId + "/stats", handler(ownerStats));
    professor.stomp().subscribe(topic(), new StompFrameHandler() {
      @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
      @Override public void handleFrame(StompHeaders headers, Object payload) { publicMessages.add(json.readTree((byte[]) payload)); }
    });
    for (int index = 0; index < 3; index++) {
      var student = user(Role.STUDENT);
      participantIds.add(participants.saveAndFlush(new RoomParticipant(roomId, student.getId(), "학생" + index)).getId());
      var connection = connect(student);
      connection.stomp().subscribe(topic(), handler(new LinkedBlockingQueue<>()));
      students.add(connection);
    }
    await(() -> presence.getOnlineParticipantIds(roomId).size() == 3);
    setId = sets.create(new QuestionSetCurrentUser(owner.getId(), "PROFESSOR", owner.getName()),
        new CreateQuestionSetRequest("실시간 테스트 세트", null, Visibility.PRIVATE)).id();
    questions.add(setId, owner.getId(), new QuestionWriteRequest(QuestionType.TRUE_FALSE, "스택은 LIFO이다.", 60,
        List.of(new ChoiceRequest("O", true), new ChoiceRequest("X", false))));
    quiz = quizzes.startQuiz(roomId, new StartQuizRequest(setId), owner.getId());
    var options = choices.findByRunQuestionIdOrderByOrderNoAsc(quiz.firstRunQuestionId());
    correctChoice = options.stream().filter(choice -> choice.isCorrect()).findFirst().orElseThrow().getId();
    wrongChoice = options.stream().filter(choice -> !choice.isCorrect()).findFirst().orElseThrow().getId();
  }

  @AfterEach void cleanup() {
    assertAll("실시간 답안 테스트 정리",
        () -> { if (roomId != null) management.close(roomId, owner.getId()); },
        () -> assertAll(sockets.stream().map(socket -> (Executable) () -> { if (socket.isOpen()) socket.close(); })),
        () -> { if (roomId != null) await(() -> source.getPresence(roomId).onlineUserIds().isEmpty()); },
        client::stop,
        () -> {
          if (roomId == null) return;
          jdbc.update("DELETE FROM answers WHERE run_question_id IN (SELECT q.id FROM run_questions q JOIN quiz_runs r ON q.quiz_run_id=r.id WHERE r.room_id=?)", roomId);
          jdbc.update("DELETE FROM run_choices WHERE run_question_id IN (SELECT q.id FROM run_questions q JOIN quiz_runs r ON q.quiz_run_id=r.id WHERE r.room_id=?)", roomId);
          jdbc.update("DELETE FROM run_questions WHERE quiz_run_id IN (SELECT id FROM quiz_runs WHERE room_id=?)", roomId);
          jdbc.update("DELETE FROM quiz_runs WHERE room_id=?", roomId);
          jdbc.update("DELETE FROM room_participants WHERE room_id=?", roomId);
          jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        },
        () -> {
          if (setId == null) return;
          jdbc.update("DELETE FROM choices WHERE question_id IN (SELECT id FROM questions WHERE question_set_id=?)", setId);
          jdbc.update("DELETE FROM questions WHERE question_set_id=?", setId);
          jdbc.update("DELETE FROM question_sets WHERE id=?", setId);
        },
        () -> assertAll(createdUsers.stream().map(user -> (Executable) () -> users.deleteById(user.getId()))));
  }

  @Test void threeOnlineStudentsAnswerAndQuestionClosesBeforeDeadline() throws Exception {
    for (int index = 0; index < 3; index++) {
      sendAnswer(index, correctChoice);
      int count = index + 1;
      await(() -> answerCount() == count);
    }
    await(this::closed);
    await(() -> events.values.stream().filter(event -> event.roomId().equals(roomId)
        && event.message() instanceof QuestionClosedMessage).count() == 1);
    var question = runQuestions.findById(quiz.firstRunQuestionId()).orElseThrow();
    assertThat(question.getClosedAt()).isBefore(question.getClosesAt());
    assertThat(events.values).filteredOn(event -> event.roomId().equals(roomId)
        && event.message() instanceof QuestionClosedMessage).hasSize(1);
  }

  @Test void unansweredStudentDisconnectsAndRemainingAnswersImmediatelyCloseQuestion() throws Exception {
    sendAnswer(0, correctChoice);
    await(() -> answerCount() == 1);
    sendAnswer(1, wrongChoice);
    await(() -> answerCount() == 2);
    assertThat(closed()).isFalse();

    students.get(2).socket().close();

    await(this::closed);
    assertThat(answerCount()).isEqualTo(2);
    assertThat(presence.getOnlineParticipantIds(roomId)).containsExactlyInAnyOrder(participantIds.get(0), participantIds.get(1));
  }

  @Test void zeroOnlineStudentsNeverCountsAsEveryoneAnswered() throws Exception {
    for (var student : students) student.socket().close();
    await(() -> presence.getOnlineParticipantIds(roomId).isEmpty());

    answers.closeIfAllAnswered(roomId);

    assertThat(closed()).isFalse();
    assertThat(answerCount()).isZero();
  }

  @Test void changingAnswerKeepsOneRowAndOnlyProfessorReceivesProgress() throws Exception {
    var errors = new LinkedBlockingQueue<JsonNode>();
    var forbiddenStats = new LinkedBlockingQueue<JsonNode>();
    students.getFirst().stomp().subscribe("/user/queue/errors", handler(errors));
    students.getFirst().stomp().subscribe("/user/queue/rooms/" + roomId + "/stats", handler(forbiddenStats));
    var rejected = errors.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(rejected).isNotNull();
    assertThat(rejected.path("code").asText()).isEqualTo("FORBIDDEN");

    sendAnswer(0, correctChoice);
    var first = ownerStats.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(first).isNotNull();
    assertThat(first.path("answeredCount").asInt()).isEqualTo(1);
    assertThat(first.size()).isEqualTo(4);
    sendAnswer(0, wrongChoice);
    await(() -> jdbc.queryForObject("SELECT run_choice_id FROM answers WHERE run_question_id=? AND participant_id=?",
        Long.class, quiz.firstRunQuestionId(), participantIds.getFirst()).equals(wrongChoice));
    assertThat(answerCount()).isEqualTo(1);
    assertThat(ownerStats.poll(700, TimeUnit.MILLISECONDS)).isNull();
    assertThat(forbiddenStats).isEmpty();
    assertThat(publicMessages).noneMatch(message -> "ANSWER_PROGRESS".equals(message.path("type").asText()));
  }

  private void sendAnswer(int student, Long choiceId) {
    var headers = new StompHeaders();
    headers.setDestination("/app/rooms/" + roomId + "/answer");
    headers.setContentType(MediaType.APPLICATION_JSON);
    students.get(student).stomp().send(headers,
        json.writeValueAsBytes(new SubmitAnswerRequest(quiz.firstRunQuestionId(), choiceId)));
  }

  private Connection connect(User user) throws Exception {
    int before = sockets.size();
    var headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken());
    headers.setHeartbeat(new long[] {0, 0});
    var session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
        new StompSessionHandlerAdapter() {}).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    return new Connection(session, sockets.get(before));
  }

  private StompFrameHandler handler(BlockingQueue<JsonNode> messages) {
    return new StompFrameHandler() {
      @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
      @Override public void handleFrame(StompHeaders headers, Object payload) { messages.add(json.readTree((byte[]) payload)); }
    };
  }

  private User user(Role role) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", "테스트", null, role));
    createdUsers.add(user);
    return user;
  }

  private int answerCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM answers WHERE run_question_id=?", Integer.class, quiz.firstRunQuestionId());
  }

  private boolean closed() { return runQuestions.findById(quiz.firstRunQuestionId()).orElseThrow().isClosed(); }
  private String topic() { return "/topic/rooms/" + roomId + "/quiz"; }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("답안·접속 상태 반영 시간 초과");
      Thread.sleep(20);
    }
  }

  private record Connection(StompSession stomp, WebSocketSession socket) {}
}
