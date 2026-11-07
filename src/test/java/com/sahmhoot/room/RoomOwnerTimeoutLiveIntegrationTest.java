package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.dto.message.RoomEndedMessage;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomOwnerTimeoutService;
import com.sahmhoot.room.service.RoomPresencePublisher;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceTracker;
import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/** 시간·예약 실행만 제어하고, 실제 JWT·/ws·#65 추적·이벤트·DB 종료·ROOM_ENDED 수신을 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoomOwnerTimeoutLiveIntegrationTest {
  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
  private static final Duration WAIT = Duration.ofSeconds(8);
  private static final StompFrameHandler IGNORE = new StompFrameHandler() {
    @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
    @Override public void handleFrame(StompHeaders headers, Object payload) {}
  };

  @TestConfiguration
  static class TimeConfig {
    @Bean @Primary MutableClock ownerLiveClock() { return new MutableClock(T0); }
    @Bean PresenceEventProbe ownerLiveEventProbe() { return new PresenceEventProbe(); }
  }

  static class PresenceEventProbe {
    final List<ObservedChange> changes = new CopyOnWriteArrayList<>();

    @EventListener void record(RoomPresenceChangedEvent event) {
      changes.add(new ObservedChange(event, TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()));
    }
  }

  @LocalServerPort int port;
  @Autowired MutableClock clock;
  @Autowired JwtProvider jwt;
  @Autowired RoomService creation;
  @Autowired RoomManagementService management;
  @Autowired RoomOwnerTimeoutService timeout;
  @Autowired RoomPresencePublisher publisher;
  @Autowired RoomPresencePort presence;
  @Autowired RoomPresenceTracker tracker;
  @Autowired PresenceEventProbe probe;
  @Autowired RoomParticipantRepository participants;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @MockitoBean RoomPresenceTaskScheduler scheduler;
  private final List<Task> tasks = new CopyOnWriteArrayList<>();
  private final List<User> createdUsers = new CopyOnWriteArrayList<>();
  private final List<WebSocketSession> transports = new CopyOnWriteArrayList<>();
  private final BlockingQueue<RoomEndedMessage> ended = new LinkedBlockingQueue<>();
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
  private Long roomId;
  private Connection ownerTab;
  private Connection studentTab;

  @BeforeEach void setup() throws Exception {
    clock.setTo(T0);
    tasks.clear();
    probe.changes.clear();
    when(scheduler.schedule(any(), any())).thenAnswer(call -> {
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      var canceled = new AtomicBoolean();
      when(future.cancel(false)).thenAnswer(cancel -> { canceled.set(true); return true; });
      tasks.add(new Task(call.getArgument(0), call.getArgument(1), future, canceled));
      return future;
    });
    owner = user(Role.PROFESSOR);
    User student = user(Role.STUDENT);
    roomId = creation.create(owner.getId(), new CreateRoomRequest("소유자 실제 연결 종료")).id();
    participants.saveAndFlush(new RoomParticipant(roomId, student.getId(), "학생"));
    studentTab = connect(student);
    studentTab.stomp().subscribe(topic(), new StompFrameHandler() {
      @Override public Type getPayloadType(StompHeaders headers) { return byte[].class; }
      @Override public void handleFrame(StompHeaders headers, Object payload) {
        if ("ROOM_ENDED".equals(json.readTree((byte[]) payload).path("type").asText())) {
          ended.add(json.readValue((byte[]) payload, RoomEndedMessage.class));
        }
      }
    });
    ownerTab = connectOwner();
    await(() -> sessionCount() == 2 && presence.getPresence(roomId).ownerOnline());
  }

  @AfterEach void cleanup() {
    assertAll("소유자 접속 테스트 정리",
        () -> { if (roomId != null) management.close(roomId, owner.getId()); },
        () -> assertAll(transports.stream().map(socket -> (Executable) () -> {
          if (socket.isOpen()) socket.close();
        })),
        () -> { if (roomId != null) await(() -> sessionCount() == 0); },
        () -> { if (roomId != null) timeout.cancelAll(roomId); },
        () -> { if (roomId != null) publisher.cancelAll(roomId); },
        client::stop,
        () -> { if (roomId != null) jdbc.update("DELETE FROM room_participants WHERE room_id = ?", roomId); },
        () -> { if (roomId != null) jdbc.update("DELETE FROM rooms WHERE id = ?", roomId); },
        () -> assertAll(createdUsers.stream().map(user -> (Executable) () -> users.deleteById(user.getId()))));
  }

  @Test void onlyLastOwnerTabClosureStartsThirtyMinutesThenStudentReceivesRoomEnded() throws Exception {
    Connection second = connectOwner();
    await(() -> sessionCount() == 3);
    ownerTab.stomp().disconnect();
    await(() -> sessionCount() == 2);
    advanceTo(T0.plusSeconds(1800));
    assertThat(status()).isEqualTo("OPEN");
    assertThat(presence.getPresence(roomId).ownerOnline()).isTrue();

    advanceTo(T0.plusSeconds(2000));
    second.socket().close(); // STOMP DISCONNECT 없이 탭을 닫는 경로
    await(() -> sessionCount() == 1);
    assertThat(presence.getPresence(roomId).ownerOnline()).isFalse();
    Task timer = at(T0.plusSeconds(3800));
    advanceTo(T0.plusSeconds(3799));
    assertThat(status()).isEqualTo("OPEN");
    advanceTo(T0.plusSeconds(3800));
    assertThat(status()).isEqualTo("CLOSED");
    assertThat(closedAt()).isEqualTo(T0.plusSeconds(3800));
    assertEnded(T0.plusSeconds(3800));
    timer.action().run();
    assertThat(ended.poll(200, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test void reconnectBeforeDeadlineCancelsAndNextDisconnectStartsFreshThirtyMinutes() throws Exception {
    ownerTab.socket().close();
    await(() -> sessionCount() == 1);
    Task old = at(T0.plusSeconds(1800));
    advanceTo(T0.plusSeconds(1799));
    Connection reconnected = connectOwner();
    await(() -> presence.getPresence(roomId).ownerOnline());
    assertThat(old.canceled().get()).isTrue();
    advanceTo(T0.plusSeconds(1900));
    assertThat(status()).isEqualTo("OPEN");

    reconnected.stomp().disconnect();
    await(() -> sessionCount() == 1);
    old.action().run(); // 예전 예약이 뒤늦게 실행돼도 새 예약을 지우지 않는다.
    advanceTo(T0.plusSeconds(3699));
    assertThat(status()).isEqualTo("OPEN");
    advanceTo(T0.plusSeconds(3700));
    assertThat(status()).isEqualTo("CLOSED");
    assertThat(closedAt()).isEqualTo(T0.plusSeconds(3700));
    assertEnded(T0.plusSeconds(3700));
  }

  @Test void manualCloseCancelsActualDisconnectTimerAndCannotNotifyTwice() throws Exception {
    ownerTab.stomp().disconnect();
    await(() -> sessionCount() == 1);
    Task timer = at(T0.plusSeconds(1800));
    advanceTo(T0.plusSeconds(60));
    management.close(roomId, owner.getId());
    assertThat(timer.canceled().get()).isTrue();
    assertEnded(T0.plusSeconds(60));
    advanceTo(T0.plusSeconds(1800));
    timer.action().run();
    assertThat(closedAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(ended.poll(200, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test void studentDisconnectDoesNotStartOwnerTimeout() throws Exception {
    studentTab.stomp().disconnect();
    await(() -> sessionCount() == 1);
    advanceTo(T0.plusSeconds(3600));
    assertThat(status()).isEqualTo("OPEN");
    assertThat(presence.getPresence(roomId).ownerOnline()).isTrue();
  }

  @Test void actualTrackerEventsHaveNoTransactionAndClockRollbackPreservesStateChangeOrder() throws Exception {
    clock.setTo(T0.plusSeconds(600));
    ownerTab.stomp().disconnect();
    await(() -> sessionCount() == 1);
    clock.setTo(T0.plusSeconds(300));
    Connection reconnected = connect(owner);
    var subscription = reconnected.stomp().subscribe(topic(), IGNORE);
    await(() -> presence.getPresence(roomId).ownerOnline());
    clock.setTo(T0.plusSeconds(100));
    subscription.unsubscribe();
    await(() -> sessionCount() == 1);

    var observed = probe.changes.stream().filter(change -> change.event().current().roomId().equals(roomId)).toList();
    assertThat(observed).extracting(ObservedChange::transactionBound).containsOnly(false);
    var ownerChanges = observed.stream().map(ObservedChange::event)
        .filter(RoomPresenceChangedEvent::ownerConnectionChanged).toList();
    assertThat(ownerChanges).extracting(event -> event.current().ownerOnline()).containsExactly(true, false, true, false);
    assertThat(ownerChanges).extracting(RoomPresenceChangedEvent::occurredAt)
        .containsExactly(T0, T0.plusSeconds(600), T0.plusSeconds(300), T0.plusSeconds(100));
    assertThat(at(T0.plusSeconds(1900))).isNotNull();
    advanceTo(T0.plusSeconds(1899));
    assertThat(status()).isEqualTo("OPEN");
    advanceTo(T0.plusSeconds(1900));
    assertThat(status()).isEqualTo("CLOSED");
    assertEnded(T0.plusSeconds(1900));
  }

  private Connection connectOwner() throws Exception {
    Connection connection = connect(owner);
    connection.stomp().subscribe(topic(), IGNORE);
    return connection;
  }

  private Connection connect(User user) throws Exception {
    int before = transports.size();
    var headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken());
    headers.setHeartbeat(new long[] {0, 0});
    var stomp = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(),
        headers, new StompSessionHandlerAdapter() {}).get(WAIT.toSeconds(), TimeUnit.SECONDS);
    await(() -> transports.size() > before);
    return new Connection(stomp, transports.get(before));
  }

  private User user(Role role) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", "사용자", null, role));
    createdUsers.add(user);
    return user;
  }

  private void advanceTo(Instant at) {
    for (int attempt = 0; attempt < 100; attempt++) {
      var next = tasks.stream().filter(task -> !task.canceled().get() && !task.at().isAfter(at))
          .min(Comparator.comparing(Task::at));
      if (next.isEmpty()) { clock.setTo(at); return; }
      Task task = next.get();
      tasks.remove(task);
      if (clock.instant().isBefore(task.at())) clock.setTo(task.at());
      task.action().run();
    }
    throw new AssertionError("예약 실행이 끝나지 않습니다.");
  }

  private Task at(Instant at) {
    return tasks.stream().filter(task -> !task.canceled().get() && task.at().equals(at))
        .findFirst().orElseThrow();
  }

  private void assertEnded(Instant at) throws InterruptedException {
    assertThat(ended.poll(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(RoomEndedMessage.at(at));
  }

  private int sessionCount() { return ReflectionTestUtils.invokeMethod(tracker, "sessionCount", roomId); }
  private String topic() { return "/topic/rooms/" + roomId + "/quiz"; }
  private String status() { return jdbc.queryForObject("SELECT status FROM rooms WHERE id = ?", String.class, roomId); }
  private Instant closedAt() {
    return jdbc.queryForObject("SELECT closed_at FROM rooms WHERE id = ?", LocalDateTime.class, roomId).toInstant(ZoneOffset.UTC);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) throw new AssertionError("접속 상태 반영 시간 초과");
      Thread.sleep(20);
    }
  }

  private record Connection(StompSession stomp, WebSocketSession socket) {}
  private record Task(Instant at, Runnable action, ScheduledFuture<?> future, AtomicBoolean canceled) {}
  private record ObservedChange(RoomPresenceChangedEvent event, boolean transactionBound) {}
}
