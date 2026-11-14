package com.sahmhoot.websocket;

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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 실제 서버(/ws)에 STOMP로 연결해 SUBSCRIBE receipt를 확인한다(#64). 클라이언트는 RECEIPT를 받은 뒤 state를 조회하므로,
 * RECEIPT 직후 발행한 메시지를 반드시 받아야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SubscribeReceiptStompIntegrationTest {

  private static final Duration WAIT = Duration.ofSeconds(5);

  @LocalServerPort int port;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired RoomParticipantRepository participants;
  @Autowired JdbcTemplate jdbc;
  @Autowired SimpMessagingTemplate messaging;
  @Autowired @Qualifier("brokerChannel") AbstractSubscribableChannel brokerChannel;

  private final WebSocketStompClient client =
      new WebSocketStompClient(new StandardWebSocketClient());
  private final List<StompSession> opened = new ArrayList<>();
  private final List<Long> userIds = new ArrayList<>();
  private final List<Long> roomIds = new ArrayList<>();
  private User owner;
  private User student;
  private Room room;

  @BeforeEach
  void setup() {
    // receipt를 기다리려면 클라이언트에 스케줄러가 필요하다(제한시간 처리용).
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    client.setTaskScheduler(scheduler);
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    room = room(owner);
    join(room, student, "익명학생");
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
  void 방_채널_구독은_RECEIPT를_받고_그_직후_발행된_메시지를_놓치지_않는다() throws Exception {
    StompSession session = connect(student);
    List<String> received = new CopyOnWriteArrayList<>();
    String topic = "/topic/rooms/" + room.getId() + "/quiz";

    CompletableFuture<Void> receipted = new CompletableFuture<>();
    StompSession.Receiptable subscription =
        session.subscribe(withReceipt(topic), collect(received));
    subscription.addReceiptTask(() -> {
      // 클라이언트가 RECEIPT를 받자마자 state를 조회하는 상황. 이 시점에 발행된 메시지도 와야 한다.
      messaging.convertAndSend(topic, (Object) Map.of("type", "ROOM_WAITING"));
      receipted.complete(null);
    });

    receipted.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    await(() -> !received.isEmpty());
    assertThat(received).singleElement().asString().contains("ROOM_WAITING");
  }

  @Test
  void 사용자_큐_구독도_등록된_뒤_RECEIPT를_받는다() throws Exception {
    StompSession session = connect(student);
    List<String> received = new CopyOnWriteArrayList<>();
    String queue = "/user/queue/rooms/" + room.getId() + "/me";

    CompletableFuture<Void> receipted = new CompletableFuture<>();
    session.subscribe(withReceipt(queue), collect(received))
        .addReceiptTask(() -> {
          messaging.convertAndSendToUser(
              String.valueOf(student.getId()),
              "/queue/rooms/" + room.getId() + "/me",
              (Object) Map.of("type", "MY_STATUS"));
          receipted.complete(null);
        });

    receipted.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    await(() -> !received.isEmpty());
    assertThat(received).singleElement().asString().contains("MY_STATUS");
  }

  @Test
  void 같은_세션에_보내는_메시지가_전달_중이면_구독이_등록될_때까지_RECEIPT를_보내지_않는다() throws Exception {
    StompSession session = connect(student);
    CompletableFuture<Void> errorsReady = new CompletableFuture<>();
    session.subscribe(withReceipt("/user/queue/errors"), collect(new CopyOnWriteArrayList<>()))
        .addReceiptTask(() -> errorsReady.complete(null));
    errorsReady.get(WAIT.toSeconds(), TimeUnit.SECONDS);

    // 이 세션으로 가는 사용자 메시지를 브로커 채널에서 멈춘다. 같은 세션의 /user 구독은 그 뒤로 대기열에 들어간다.
    CountDownLatch blocked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ChannelInterceptor blocker =
        new ChannelInterceptor() {
          @Override
          public Message<?> preSend(Message<?> message, MessageChannel channel) {
            String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
            if (destination != null && destination.startsWith("/queue/errors-user")) {
              blocked.countDown();
              try {
                release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            return message;
          }
        };
    brokerChannel.addInterceptor(blocker);
    try {
      CompletableFuture.runAsync(
          () ->
              messaging.convertAndSendToUser(
                  String.valueOf(student.getId()),
                  "/queue/errors",
                  (Object) Map.of("type", "ERROR")));
      assertThat(blocked.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

      CompletableFuture<Void> meReceipt = new CompletableFuture<>();
      session
          .subscribe(withReceipt(userQueue("me")), collect(new ArrayList<>()))
          .addReceiptTask(() -> meReceipt.complete(null));
      Thread.sleep(300);
      assertThat(meReceipt).isNotDone();

      release.countDown();
      meReceipt.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    } finally {
      release.countDown();
      brokerChannel.removeInterceptor(blocker);
    }
  }

  @Test
  void 권한_검사에서_거부된_구독은_RECEIPT를_받지_않는다() throws Exception {
    StompSession session = connect(student);
    CompletableFuture<Void> statsReceipt = new CompletableFuture<>();
    CompletableFuture<Void> quizReceipt = new CompletableFuture<>();

    // 소유자 전용 stats 큐는 학생에게 거부된다. 같은 세션의 프레임은 순서대로 처리되므로 뒤의 구독 RECEIPT가 오면 앞의 처리도 끝났다.
    session
        .subscribe(withReceipt(userQueue("stats")), collect(new ArrayList<>()))
        .addReceiptTask(() -> statsReceipt.complete(null));
    session
        .subscribe(withReceipt(topic("quiz")), collect(new ArrayList<>()))
        .addReceiptTask(() -> quizReceipt.complete(null));

    quizReceipt.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    Thread.sleep(200);
    assertThat(statsReceipt).isNotDone();
  }

  @Test
  void receipt_헤더가_없는_구독은_기존처럼_동작한다() throws Exception {
    StompSession session = connect(student);
    List<String> received = new CopyOnWriteArrayList<>();
    String topic = "/topic/rooms/" + room.getId() + "/presence";
    session.subscribe(topic, collect(received));

    // 구독 등록 시점을 알 수 없으므로 메시지가 올 때까지 다시 보낸다.
    await(() -> {
      messaging.convertAndSend(topic, (Object) Map.of("type", "ONLINE_COUNT"));
      return !received.isEmpty();
    });
    assertThat(received.getFirst()).contains("ONLINE_COUNT");
  }

  private String topic(String channel) {
    return "/topic/rooms/" + room.getId() + "/" + channel;
  }

  private String userQueue(String channel) {
    return "/user/queue/rooms/" + room.getId() + "/" + channel;
  }

  private static StompHeaders withReceipt(String destination) {
    StompHeaders headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setReceipt(UUID.randomUUID().toString());
    return headers;
  }

  private static StompFrameHandler collect(List<String> received) {
    return new StompFrameHandler() {
      @Override
      public Type getPayloadType(StompHeaders headers) {
        return byte[].class;
      }

      @Override
      public void handleFrame(StompHeaders headers, Object payload) {
        received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
      }
    };
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

  private static void await(java.util.function.BooleanSupplier condition)
      throws InterruptedException {
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

  private Room room(User host) {
    var codes = new JoinCodeGenerator();
    String code;
    do {
      code = codes.generate();
    } while (rooms.existsByCode(code));
    Room saved = rooms.saveAndFlush(new Room(host.getId(), "구독 receipt", code));
    roomIds.add(saved.getId());
    join(saved, host, "담당교수");
    return saved;
  }

  private void join(Room target, User user, String nickname) {
    participants.saveAndFlush(new RoomParticipant(target.getId(), user.getId(), nickname));
  }
}
