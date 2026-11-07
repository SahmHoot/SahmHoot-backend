package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.dto.message.OnlineCountMessage;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.websocket.presence.RoomPresenceTracker;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JWT·MySQL·/ws·집계·스케줄러·REST를 함께 검증한다. 접속 소스와 발행기를 대체하지 않는다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoomPresenceLiveIntegrationTest {
  private static final Duration WAIT = Duration.ofSeconds(8);
  private static final StompFrameHandler IGNORE = new StompFrameHandler() {
    @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
    @Override public void handleFrame(StompHeaders headers, Object payload) {}
  };

  @LocalServerPort int port;
  @Autowired RoomService creation;
  @Autowired RoomManagementService management;
  @Autowired RoomParticipantRepository participants;
  @Autowired ParticipantPresencePort presence;
  @Autowired RoomPresenceTracker tracker;
  @Autowired UserRepository users;
  @Autowired JwtProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;

  private final List<User> createdUsers = new ArrayList<>();
  private final List<Connection> connections = new ArrayList<>();
  private final List<WebSocketSession> transports = new CopyOnWriteArrayList<>();
  private final BlockingQueue<OnlineCountMessage> counts = new LinkedBlockingQueue<>();
  private final StandardWebSocketClient transportClient = new StandardWebSocketClient();
  private final WebSocketStompClient client = new WebSocketStompClient(new WebSocketClient() {
    @Override public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler,
        String uriTemplate, Object... variables) {
      return record(transportClient.execute(handler, uriTemplate, variables));
    }
    @Override public CompletableFuture<WebSocketSession> execute(WebSocketHandler handler,
        WebSocketHttpHeaders headers, URI uri) {
      return record(transportClient.execute(handler, headers, uri));
    }
    private CompletableFuture<WebSocketSession> record(CompletableFuture<WebSocketSession> result) {
      return result.thenApply(socket -> { transports.add(socket); return socket; });
    }
  });
  private User owner;
  private User student;
  private User secondStudent;
  private Long roomId;
  private Long studentId;
  private Long secondStudentId;

  @BeforeEach void setup() throws Exception {
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    secondStudent = user(Role.STUDENT);
    roomId = creation.create(owner.getId(), new CreateRoomRequest("실제 접속 집계")).id();
    studentId = join(student, "학생1");
    secondStudentId = join(secondStudent, "학생2");
    connect(owner).stomp().subscribe(topic("presence"), new StompFrameHandler() {
      @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
      @Override public void handleFrame(StompHeaders headers, Object payload) {
        counts.add(json.readValue((byte[]) payload, OnlineCountMessage.class));
      }
    });
    await(() -> sessionCount() == 1);
  }

  @AfterEach void cleanup() {
    // 한 단계가 실패해도 나머지 자원과 DB 정리를 모두 시도하고, 실패는 테스트 결과에 함께 남긴다.
    assertAll("실제 접속 테스트 정리",
        () -> { if (roomId != null) management.close(roomId, owner.getId()); },
        () -> assertAll(transports.stream().map(socket -> (Executable) () -> {
          if (socket.isOpen()) socket.close();
        })),
        () -> { if (roomId != null) await(() -> sessionCount() == 0); },
        client::stop,
        () -> { if (roomId != null) jdbc.update("DELETE FROM room_participants WHERE room_id = ?", roomId); },
        () -> { if (roomId != null) jdbc.update("DELETE FROM rooms WHERE id = ?", roomId); },
        () -> assertAll(createdUsers.stream().map(user -> (Executable) () -> users.deleteById(user.getId()))));
  }

  @Test void twoTabsCountOnceAndOnlyLastSocketClosureDecreasesRestAndRealtimeCount() throws Exception {
    assertCounts(0);
    assertThat(presence.getOnlineParticipantIds(roomId)).isEmpty(); // 교수는 참여자여도 제외한다.
    Connection tab1 = connect(student);
    Connection tab2 = connect(student);
    tab1.stomp().subscribe(topic("quiz"), IGNORE);
    tab2.stomp().subscribe(topic("chat"), IGNORE);
    await(() -> sessionCount() == 3);
    assertThat(presence.getOnlineParticipantIds(roomId)).containsExactly(studentId);
    assertCounts(1);
    OnlineCountMessage joined = nextCount(1);

    tab1.stomp().disconnect();
    await(() -> sessionCount() == 2);
    assertCounts(1);
    assertThat(counts.poll(1200, TimeUnit.MILLISECONDS)).isNull();

    // STOMP DISCONNECT 없이 소켓을 닫아 탭 종료에 해당하는 서버의 끊김 경로도 거친다.
    tab2.socket().close(CloseStatus.NORMAL);
    await(() -> sessionCount() == 1);
    assertThat(presence.getOnlineParticipantIds(roomId)).isEmpty();
    assertCounts(0);
    OnlineCountMessage left = nextCount(0);
    assertThat(Duration.between(joined.sentAt(), left.sentAt())).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
  }

  @Test void latestFullCountIsPublishedAndOnlyLastRoomSubscriptionRemovalDecreasesIt() throws Exception {
    StompSession first = connect(student).stomp();
    var quiz = first.subscribe(topic("quiz"), IGNORE);
    var chat = first.subscribe(topic("chat"), IGNORE);
    connect(secondStudent).stomp().subscribe(topic("quiz"), IGNORE);
    await(() -> sessionCount() == 3);
    assertThat(presence.getOnlineParticipantIds(roomId)).containsExactlyInAnyOrder(studentId, secondStudentId);
    assertCounts(2);
    OnlineCountMessage previous = null;
    do {
      OnlineCountMessage message = counts.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(message).isNotNull();
      if (previous != null) {
        assertThat(Duration.between(previous.sentAt(), message.sentAt())).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
      }
      previous = message;
    } while (previous.onlineCount() != 2);

    quiz.unsubscribe();
    assertCounts(2);
    assertThat(counts.poll(1200, TimeUnit.MILLISECONDS)).isNull();
    chat.unsubscribe();
    await(() -> sessionCount() == 2);
    assertThat(presence.getOnlineParticipantIds(roomId)).containsExactly(secondStudentId);
    assertCounts(1);
    nextCount(1);
  }

  @Test void roomCloseCancelsPendingPublicationAndLateSocketClosureCannotPublishCount() throws Exception {
    connect(student).stomp().subscribe(topic("quiz"), IGNORE);
    await(() -> sessionCount() == 2);
    management.close(roomId, owner.getId());
    counts.clear(); // 종료 전에 이미 발행된 알림은 검사 대상이 아니다.
    connections.getLast().socket().close();
    await(() -> sessionCount() == 1);
    assertThat(counts.poll(1500, TimeUnit.MILLISECONDS)).isNull();
    assertThat(get("/api/rooms/" + roomId + "/state", student).statusCode()).isEqualTo(409);
  }

  private OnlineCountMessage nextCount(int expected) throws InterruptedException {
    var message = counts.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertThat(message).isNotNull();
    assertThat(message.type()).isEqualTo("ONLINE_COUNT");
    assertThat(message.sentAt()).isNotNull();
    assertThat(message.onlineCount()).isEqualTo(expected);
    return message;
  }

  private void assertCounts(int expected) throws Exception {
    var mine = get("/api/rooms/mine", owner);
    assertThat(mine.statusCode()).isEqualTo(200);
    var room = json.readTree(mine.body()).valueStream()
        .filter(value -> value.path("id").asLong() == roomId).findFirst().orElseThrow();
    assertThat(room.path("onlineCount").asInt()).isEqualTo(expected);
    var state = get("/api/rooms/" + roomId + "/state", student);
    assertThat(state.statusCode()).isEqualTo(200);
    assertThat(json.readTree(state.body()).path("onlineCount").asInt()).isEqualTo(expected);
  }

  private HttpResponse<String> get(String path, User user) throws Exception {
    try (var http = HttpClient.newHttpClient()) {
      var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
          .header("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken())
          .timeout(WAIT).GET().build();
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private Connection connect(User user) throws Exception {
    int before = transports.size();
    var headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken());
    headers.setHeartbeat(new long[] {0, 0});
    var session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
        headers, new StompSessionHandlerAdapter() {}).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    await(() -> transports.size() > before);
    var connection = new Connection(session, transports.get(before));
    connections.add(connection);
    return connection;
  }

  private User user(Role role) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", "사용자", null, role));
    createdUsers.add(user);
    return user;
  }

  private Long join(User user, String nickname) {
    return participants.saveAndFlush(new RoomParticipant(roomId, user.getId(), nickname)).getId();
  }

  private String topic(String channel) { return "/topic/rooms/" + roomId + "/" + channel; }

  private int sessionCount() {
    // #65가 제공하는 처리 완료 확인용 훅. 운영 코드는 공개 조회 포트만 사용한다.
    return ReflectionTestUtils.invokeMethod(tracker, "sessionCount", roomId);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("접속 상태 반영 시간 초과");
      Thread.sleep(20);
    }
  }

  private record Connection(StompSession stomp, WebSocketSession socket) {}
}
