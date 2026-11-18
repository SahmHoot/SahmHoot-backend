package com.sahmhoot.reaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.JoinCodeGenerator;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomStateService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 실제 서버(/ws)에 STOMP로 연결해 이모지 반응(#59)을 확인한다. 학생 반응이 방 전체로 퍼지고, 소유자에게만 집계가 가며,
 * 1초 3건 제한(#13 인터셉터)과 오류 응답이 요청한 세션에만 가는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReactionStompIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(5);

  @LocalServerPort int port;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired RoomParticipantRepository participants;
  @Autowired RoomStateService roomState;
  @Autowired RoomManagementService roomManagement;
  @Autowired JdbcTemplate jdbc;

  private final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
  private final List<StompSession> opened = new ArrayList<>();
  private final List<Long> userIds = new ArrayList<>();
  private final List<Long> roomIds = new ArrayList<>();
  private User owner;
  private User student1;
  private User student2;
  private Room room;

  @BeforeEach
  void setup() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    client.setTaskScheduler(scheduler);
    owner = user(Role.PROFESSOR);
    student1 = user(Role.STUDENT);
    student2 = user(Role.STUDENT);
    room = room(owner);
    join(room, student1, "학생하나");
    join(room, student2, "학생둘");
  }

  @AfterEach
  void cleanup() {
    opened.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    for (Long roomId : roomIds) {
      jdbc.update("DELETE FROM room_participants WHERE room_id = ?", roomId);
      rooms.deleteById(roomId);
    }
    userIds.forEach(users::deleteById);
  }

  @Test
  void 학생_반응은_모든_참여자에게_퍼지고_소유자에게만_집계가_간다() throws Exception {
    var ownerSession = connect(owner);
    var studentSession = connect(student1);
    var otherSession = connect(student2);
    List<String> ownerReactions = subscribe(ownerSession, topic());
    List<String> studentReactions = subscribe(studentSession, topic());
    List<String> otherReactions = subscribe(otherSession, topic());
    List<String> ownerStats = subscribe(ownerSession, stats());
    List<String> studentErrors = subscribe(studentSession, "/user/queue/errors");

    send(studentSession, "{\"emoji\":\"👍\"}");

    for (var received : List.of(ownerReactions, studentReactions, otherReactions)) {
      await(() -> !received.isEmpty());
      assertThat(received).singleElement().asString()
          .contains("\"type\":\"REACTION\"").contains("\"emoji\":\"👍\"").doesNotContain("nickname");
    }
    await(() -> !ownerStats.isEmpty());
    assertThat(ownerStats.getLast()).contains("\"type\":\"REACTION_STATS\"").contains("\"👍\":1")
        .contains("\"❓\":0");
    assertThat(studentErrors).isEmpty();

    assertThat(roomState.state(room.getId(), owner.getId()).reactionStats())
        .containsExactly(Map.entry("👍", 1), Map.entry("❓", 0), Map.entry("😀", 0), Map.entry("👏", 0));
    assertThat(roomState.state(room.getId(), student1.getId()).reactionStats()).isNull();
  }

  @Test
  void 같은_학생의_4번째_반응은_1초_3건_제한으로_거부된다() throws Exception {
    var studentSession = connect(student1);
    var otherSession = connect(student2);
    List<String> reactions = subscribe(otherSession, topic());
    List<String> errors = subscribe(studentSession, "/user/queue/errors");
    List<String> otherErrors = subscribe(otherSession, "/user/queue/errors");

    for (int i = 0; i < 4; i++) send(studentSession, "{\"emoji\":\"👏\"}");

    await(() -> !errors.isEmpty());
    assertThat(errors).singleElement().asString().contains("RATE_LIMITED");
    await(() -> reactions.size() >= 3);
    Thread.sleep(300);
    assertThat(reactions).hasSize(3);
    assertThat(otherErrors).isEmpty();
  }

  @Test
  void 소유자_반응과_허용되지_않은_이모지는_보낸_세션에만_오류를_보낸다() throws Exception {
    var ownerSession = connect(owner);
    var studentSession = connect(student1);
    List<String> reactions = subscribe(studentSession, topic());
    List<String> ownerErrors = subscribe(ownerSession, "/user/queue/errors");
    List<String> studentErrors = subscribe(studentSession, "/user/queue/errors");

    send(ownerSession, "{\"emoji\":\"👍\"}");
    await(() -> !ownerErrors.isEmpty());
    assertThat(ownerErrors).singleElement().asString().contains("\"code\":\"FORBIDDEN\"");

    send(studentSession, "{\"emoji\":\"😄\"}");
    await(() -> !studentErrors.isEmpty());
    assertThat(studentErrors).singleElement().asString().contains("\"code\":\"VALIDATION_FAILED\"");

    send(studentSession, "not json");
    await(() -> studentErrors.size() >= 2);
    assertThat(studentErrors.getLast()).contains("\"code\":\"VALIDATION_FAILED\"");

    Thread.sleep(300);
    assertThat(reactions).isEmpty();
    assertThat(ownerErrors).hasSize(1);
  }

  @Test
  void 종료된_방에는_반응을_보낼_수_없고_집계도_비운다() throws Exception {
    var studentSession = connect(student1);
    List<String> reactions = subscribe(studentSession, topic());
    List<String> errors = subscribe(studentSession, "/user/queue/errors");
    send(studentSession, "{\"emoji\":\"❓\"}");
    await(() -> !reactions.isEmpty());

    roomManagement.close(room.getId(), owner.getId());
    send(studentSession, "{\"emoji\":\"❓\"}");

    await(() -> !errors.isEmpty());
    assertThat(errors).singleElement().asString().contains("\"code\":\"ROOM_CLOSED\"");
    assertThat(reactions).hasSize(1);
  }

  private String topic() {
    return "/topic/rooms/" + room.getId() + "/reaction";
  }

  private String stats() {
    return "/user/queue/rooms/" + room.getId() + "/stats";
  }

  private String reaction() {
    return "/app/rooms/" + room.getId() + "/reaction";
  }

  private void send(StompSession session, String json) {
    StompHeaders headers = new StompHeaders();
    headers.setDestination(reaction());
    headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
    session.send(headers, json.getBytes(StandardCharsets.UTF_8));
  }

  /** RECEIPT를 받을 때까지 기다려 구독이 등록된 뒤에 전송한다. */
  private List<String> subscribe(StompSession session, String destination) throws Exception {
    List<String> received = new CopyOnWriteArrayList<>();
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setReceipt(UUID.randomUUID().toString());
    CompletableFuture<Void> ready = new CompletableFuture<>();
    session.subscribe(headers, new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders frameHeaders) {
        return byte[].class;
      }

      @Override
      public void handleFrame(StompHeaders frameHeaders, Object payload) {
        received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
      }
    }).addReceiptTask(() -> ready.complete(null));
    ready.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    return received;
  }

  private StompSession connect(User user) throws Exception {
    StompHeaders headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken());
    headers.setHeartbeat(new long[] {0, 0});
    StompSession session = client.connectAsync("ws://localhost:" + port + "/ws",
        new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {})
        .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    opened.add(session);
    return session;
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("조건이 " + WAIT.toSeconds() + "초 안에 충족되지 않았습니다.");
      }
      Thread.sleep(20);
    }
  }

  private User user(Role role) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only",
        role == Role.PROFESSOR ? "담당교수" : "학생실명", null, role));
    userIds.add(user.getId());
    return user;
  }

  private Room room(User host) {
    var codes = new JoinCodeGenerator();
    String code;
    do {
      code = codes.generate();
    } while (rooms.existsByCode(code));
    Room saved = rooms.saveAndFlush(new Room(host.getId(), "반응 확인", code));
    roomIds.add(saved.getId());
    join(saved, host, "담당교수");
    return saved;
  }

  private void join(Room target, User user, String nickname) {
    participants.saveAndFlush(new RoomParticipant(target.getId(), user.getId(), nickname));
  }
}
