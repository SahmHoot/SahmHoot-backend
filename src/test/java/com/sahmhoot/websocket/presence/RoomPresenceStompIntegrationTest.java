package com.sahmhoot.websocket.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.JoinCodeGenerator;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 실제 서버(/ws)에 STOMP로 연결해 접속 추적을 확인한다. 거부된 구독이 집계되지 않는지는 순서 보장 경로
 * (StompSubProtocolHandler → 순서 보장 대기열 → 인터셉터)를 거쳐야 의미가 있어 단위 테스트로 대신하지 않는다.
 *
 * <p>같은 세션의 프레임은 받은 순서대로 인터셉터를 통과한다. 그래서 마지막에 보낸 구독이 반영된 것을 확인하면 그보다
 * 앞서 보낸 프레임도 처리가 끝난 것이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RoomPresenceStompIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(5);

  private static final StompFrameHandler IGNORE =
      new StompFrameHandler() {
        @Override
        public Type getPayloadType(StompHeaders headers) {
          return byte[].class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {}
      };

  @TestConfiguration
  static class RecorderConfig {
    @Bean
    EventRecorder presenceEventRecorder() {
      return new EventRecorder();
    }
  }

  /** 다른 스레드(WebSocket·인바운드 실행기)에서 발행된 이벤트도 받도록 빈으로 기록한다. */
  static class EventRecorder {
    final List<RoomPresenceChangedEvent> changes = new CopyOnWriteArrayList<>();
    final List<SessionDisconnectEvent> disconnects = new CopyOnWriteArrayList<>();

    @EventListener
    public void onChange(RoomPresenceChangedEvent event) {
      changes.add(event);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
      disconnects.add(event);
    }
  }

  @LocalServerPort int port;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired RoomParticipantRepository participants;
  @Autowired JdbcTemplate jdbc;
  @Autowired RoomPresencePort presence;
  @Autowired RoomPresenceTracker tracker;
  @Autowired ApplicationEventPublisher publisher;
  @Autowired EventRecorder recorder;

  private final WebSocketStompClient client =
      new WebSocketStompClient(new StandardWebSocketClient());
  private final List<StompSession> opened = new ArrayList<>();
  private final List<Long> userIds = new ArrayList<>();
  private final List<Long> roomIds = new ArrayList<>();
  private User owner;
  private User student;
  private User outsider;
  private Room room;
  private Room otherRoom;

  @BeforeEach
  void setup() {
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    outsider = user(Role.STUDENT);
    room = room(owner);
    join(room, student, "익명학생");
    // outsider는 room에는 없고 otherRoom에만 있다.
    otherRoom = room(owner);
    join(otherRoom, student, "다른방학생");
    join(otherRoom, outsider, "외부학생");
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
  void 같은_학생의_연결_2개는_1명이고_마지막_연결이_끊겨야_빠진다() throws Exception {
    long roomId = room.getId();
    int disconnectsBefore = recorder.disconnects.size();
    StompSession tab1 = connect(student);
    StompSession tab2 = connect(student);
    tab1.subscribe(topic(roomId, "quiz"), IGNORE);
    tab2.subscribe(topic(roomId, "quiz"), IGNORE);
    tab2.subscribe(topic(roomId, "chat"), IGNORE);
    await(() -> tracker.sessionCount(roomId) == 2);

    assertThat(presence.getPresence(roomId))
        .isEqualTo(new RoomPresenceSnapshot(roomId, Set.of(student.getId()), false));
    assertThat(changes(roomId)).hasSize(1);

    tab1.disconnect();
    await(() -> tracker.sessionCount(roomId) == 1);
    assertThat(presence.getPresence(roomId).onlineUserIds()).containsExactly(student.getId());
    assertThat(changes(roomId)).hasSize(1);

    tab2.disconnect();
    await(() -> tracker.sessionCount(roomId) == 0);
    assertThat(presence.getPresence(roomId).onlineUserIds()).isEmpty();
    assertThat(changes(roomId)).hasSize(2);

    // 같은 세션의 끊김 이벤트가 다시 와도 변화가 없다.
    await(() -> recorder.disconnects.size() >= disconnectsBefore + 2);
    List.copyOf(recorder.disconnects).forEach(publisher::publishEvent);
    assertThat(presence.getPresence(roomId).onlineUserIds()).isEmpty();
    assertThat(changes(roomId)).hasSize(2);
  }

  @Test
  void 참여자가_아닌_사용자의_거부된_구독은_집계하지_않는다() throws Exception {
    StompSession tab = connect(outsider);
    tab.subscribe(topic(room.getId(), "quiz"), IGNORE);
    tab.subscribe(topic(otherRoom.getId(), "quiz"), IGNORE);
    await(() -> tracker.sessionCount(otherRoom.getId()) == 1);

    assertThat(presence.getPresence(room.getId()).onlineUserIds()).isEmpty();
    assertThat(changes(room.getId())).isEmpty();
  }

  @Test
  void 구독_직후_해제하면_순서대로_반영되어_접속자로_남지_않는다() throws Exception {
    StompSession tab = connect(student);
    tab.subscribe(topic(room.getId(), "quiz"), IGNORE).unsubscribe();
    tab.subscribe(topic(otherRoom.getId(), "quiz"), IGNORE);
    await(() -> tracker.sessionCount(otherRoom.getId()) == 1);

    assertThat(presence.getPresence(room.getId()).onlineUserIds()).isEmpty();
    assertThat(changes(room.getId())).hasSize(2);
  }

  @Test
  void 소유자_연결과_끊김을_소유자_연결_변화로_알린다() throws Exception {
    long roomId = room.getId();
    StompSession ownerTab = connect(owner);
    ownerTab.subscribe("/user/queue/rooms/" + roomId + "/stats", IGNORE);
    await(() -> tracker.sessionCount(roomId) == 1);

    assertThat(presence.getPresence(roomId))
        .isEqualTo(new RoomPresenceSnapshot(roomId, Set.of(owner.getId()), true));
    assertThat(changes(roomId)).hasSize(1);
    assertThat(changes(roomId).get(0).ownerConnectionChanged()).isTrue();

    ownerTab.disconnect();
    await(() -> tracker.sessionCount(roomId) == 0);

    assertThat(presence.getPresence(roomId))
        .isEqualTo(new RoomPresenceSnapshot(roomId, Set.of(), false));
    assertThat(changes(roomId)).hasSize(2);
    assertThat(changes(roomId).get(1).ownerConnectionChanged()).isTrue();
  }

  private StompSession connect(User user) throws Exception {
    StompHeaders headers = new StompHeaders();
    headers.add(
        "Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken());
    // 테스트 클라이언트에는 하트비트용 스케줄러가 없으므로 하트비트를 끈다.
    headers.setHeartbeat(new long[] {0, 0});
    StompSession session =
        client
            .connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                headers,
                new StompSessionHandlerAdapter() {})
            .get(WAIT.toSeconds(), TimeUnit.SECONDS);
    opened.add(session);
    return session;
  }

  private List<RoomPresenceChangedEvent> changes(long roomId) {
    return recorder.changes.stream().filter(e -> e.current().roomId() == roomId).toList();
  }

  private static String topic(long roomId, String channel) {
    return "/topic/rooms/" + roomId + "/" + channel;
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
    var user =
        users.saveAndFlush(
            User.create(
                UUID.randomUUID() + "@test.invalid",
                "test-only",
                role == Role.PROFESSOR ? "담당교수" : "학생실명",
                null,
                role));
    userIds.add(user.getId());
    return user;
  }

  /** 실제 방 생성과 같이 소유자도 참여자로 저장한다. */
  private Room room(User host) {
    var codes = new JoinCodeGenerator();
    String code;
    do {
      code = codes.generate();
    } while (rooms.existsByCode(code));
    Room saved = rooms.saveAndFlush(new Room(host.getId(), "접속 추적", code));
    roomIds.add(saved.getId());
    join(saved, host, "담당교수");
    return saved;
  }

  private void join(Room target, User user, String nickname) {
    participants.saveAndFlush(new RoomParticipant(target.getId(), user.getId(), nickname));
  }
}
