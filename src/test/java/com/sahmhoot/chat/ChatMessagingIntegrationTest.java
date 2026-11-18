package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.chat.dto.SendChatRequest;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.event.ChatMessageEvent;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.service.ChatService;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.JoinCodeGenerator;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 JWT·STOMP 인터셉터·MySQL·state REST를 함께 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ChatMessagingIntegrationTest {
  @TestConfiguration
  static class Config {
    @Bean CommitProbe chatCommitProbe(DataSource source, RoomLockManager locks) {
      return new CommitProbe(source, locks);
    }
  }

  static class CommitProbe {
    final DataSource source;
    final RoomLockManager locks;
    final List<Observation> committed = new CopyOnWriteArrayList<>();
    volatile Long rejectRoom;

    CommitProbe(DataSource source, RoomLockManager locks) { this.source = source; this.locks = locks; }

    @EventListener
    public void reject(ChatMessageEvent event) {
      if (event.roomId().equals(rejectRoom)) throw new IllegalStateException("Force rollback before commit");
    }

    @TransactionalEventListener
    public void committed(ChatMessageEvent event) throws Exception {
      // 트랜잭션에 묶이지 않은 별도 연결에서 행이 보여야 커밋 완료로 인정한다.
      try (var connection = source.getConnection();
          var query = connection.prepareStatement("SELECT COUNT(*) FROM chat_messages WHERE id = ?")) {
        query.setLong(1, event.payload().id());
        try (var result = query.executeQuery()) {
          result.next();
          committed.add(new Observation(event.payload().id(), result.getInt(1), locks.isLocked(event.roomId())));
        }
      }
    }
  }

  record Observation(Long messageId, int visibleRows, boolean roomLocked) {}

  @LocalServerPort int port;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired RoomParticipantRepository participants;
  @Autowired ChatMessageRepository messages;
  @Autowired ChatService service;
  @Autowired RoomManagementService management;
  @Autowired JwtProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @Autowired CommitProbe probe;
  @Autowired @Qualifier("brokerChannel") AbstractSubscribableChannel brokerChannel;
  private final List<User> createdUsers = new ArrayList<>();
  private final List<Room> createdRooms = new ArrayList<>();
  private final List<StompSession> sessions = new ArrayList<>();
  private WebSocketStompClient client;
  private ThreadPoolTaskScheduler scheduler;
  private Room room;
  private User owner;
  private User student;
  private Long ownerParticipantId;
  private Long studentParticipantId;

  @BeforeEach void setup() {
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.initialize();
    client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setTaskScheduler(scheduler);
    owner = user(Role.PROFESSOR, "담당교수");
    student = user(Role.STUDENT, "학생실명");
    room = room(owner);
    ownerParticipantId = join(room, owner, owner.getName());
    studentParticipantId = join(room, student, "익명학생");
    probe.committed.clear();
    probe.rejectRoom = null;
  }

  @AfterEach void cleanup() {
    sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
    client.stop();
    scheduler.shutdown();
    probe.rejectRoom = null;
    for (Room target : createdRooms) {
      jdbc.update("DELETE FROM chat_pins WHERE room_id = ?", target.getId());
      jdbc.update("UPDATE chat_messages SET parent_id = NULL WHERE room_id = ?", target.getId());
      jdbc.update("DELETE FROM chat_messages WHERE room_id = ?", target.getId());
      jdbc.update("DELETE FROM chat_restrictions WHERE room_id = ?", target.getId());
      jdbc.update("DELETE FROM room_participants WHERE room_id = ?", target.getId());
      rooms.deleteById(target.getId());
    }
    createdUsers.forEach(user -> users.deleteById(user.getId()));
  }

  @Test void studentsAndOwnerReceiveSameStoredMessageWithAnonymousSender() throws Exception {
    var sender = connect(student);
    var receiver = connect(owner);
    var senderMessages = subscribe(sender, chatTopic());
    var ownerMessages = subscribe(receiver, chatTopic());
    send(sender, "질문😀");
    var first = take(senderMessages);
    assertThat(take(ownerMessages)).isEqualTo(first);
    assertThat(first.size()).isEqualTo(10);
    assertThat(first.path("type").asText()).isEqualTo("MESSAGE");
    assertThat(first.path("senderParticipantId").asLong()).isEqualTo(studentParticipantId);
    assertThat(first.path("nickname").asText()).isEqualTo("익명학생");
    assertThat(first.path("isOwner").asBoolean()).isFalse();
    assertThat(first.has("email")).isFalse();
    assertThat(first.path("parentId").isNull()).isTrue();
    assertThat(first.path("sentAt")).isEqualTo(first.path("createdAt"));
    assertThat(messages.findById(first.path("id").asLong()).orElseThrow().getContent()).isEqualTo("질문😀");
    send(receiver, "교수 답변");
    var reply = take(senderMessages);
    assertThat(reply.path("senderParticipantId").asLong()).isEqualTo(ownerParticipantId);
    assertThat(reply.path("nickname").asText()).isEqualTo("담당교수");
    assertThat(reply.path("isOwner").asBoolean()).isTrue();
  }

  @Test void sixthMessageAcrossTwoTabsIsRejectedOnlyInSendingTabAndNeverStored() throws Exception {
    var first = connect(student);
    var second = connect(student);
    var firstErrors = subscribe(first, "/user/queue/errors");
    var secondErrors = subscribe(second, "/user/queue/errors");
    var received = subscribe(first, chatTopic());
    for (int i = 0; i < 5; i++) {
      send(i % 2 == 0 ? first : second, "메시지" + i);
      take(received);
    }
    send(second, "6번째 거부");
    assertThat(take(secondErrors).path("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(firstErrors.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(received).isEmpty();
    assertThat(messageCount()).isEqualTo(5);
  }

  @Test void blockedParticipantCannotStoreAndTheirOtherTabDoesNotReceiveError() throws Exception {
    jdbc.update("INSERT INTO chat_restrictions VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
        room.getId(), studentParticipantId, owner.getId());
    var sender = connect(student);
    var otherTab = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    var otherErrors = subscribe(otherTab, "/user/queue/errors");
    var topic = subscribe(sender, chatTopic());
    send(sender, "차단된 전송");
    assertThat(take(errors).path("code").asText()).isEqualTo("CHAT_BLOCKED");
    assertThat(otherErrors.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(topic).isEmpty();
    assertThat(messageCount()).isZero();
    assertThat(state(student).path("me").path("chatBlocked").asBoolean()).isTrue();
  }

  @Test void alternateRoomIdSpellingCannotBypassSameRoomRateLimit() throws Exception {
    var sender = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    var received = subscribe(sender, chatTopic());
    for (int i = 0; i < 5; i++) {
      send(sender, "한도 사용" + i);
      take(received);
    }
    var headers = new StompHeaders();
    headers.setDestination("/app/rooms/000" + room.getId() + "/chat");
    headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
    sender.send(headers, "{\"content\":\"방 번호 표기로 우회\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(received.poll(1, TimeUnit.SECONDS)).as("같은 방의 6번째 메시지는 발행되지 않아야 한다").isNull();
    assertThat(take(errors).path("code").asText()).isEqualTo("RATE_LIMITED");
    assertThat(messageCount()).isEqualTo(5);
  }

  @Test void closedRoomRejectsEvenAnAlreadyConnectedSender() throws Exception {
    var sender = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    room.close(Instant.now());
    rooms.saveAndFlush(room);
    send(sender, "종료 후 전송");
    assertThat(take(errors).path("code").asText()).isEqualTo("ROOM_CLOSED");
    assertThat(messageCount()).isZero();
  }

  @Test void invalidPayloadsReturnValidationErrorsWithoutSavingOrDisconnecting() throws Exception {
    var sender = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    for (String body : List.of("{\"content\":\" \"}", "{\"content\":\"" + "가".repeat(301) + "\"}",
        "{}", "{broken", "{\"content\":\"답글\",\"parentId\":\"invalid\"}")) {
      sendRaw(sender, body);
      assertThat(take(errors).path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }
    assertThat(messageCount()).isZero();
    // 유효성 실패도 #13 전송 한도에 포함되므로 다른 사용자로 정상 연결을 확인한다.
    var teacher = connect(owner);
    var topic = subscribe(teacher, chatTopic());
    send(teacher, "정상 전송");
    assertThat(take(topic).path("content").asText()).isEqualTo("정상 전송");
    assertThat(sender.isConnected()).isTrue();
  }

  @Test void oneCharacterAnd300EmojiAreAcceptedOnTheSameConnection() throws Exception {
    var sender = connect(student);
    var topic = subscribe(sender, chatTopic());
    for (String content : List.of("가", "😀".repeat(300))) {
      send(sender, content);
      assertThat(take(topic).path("content").asText()).isEqualTo(content);
    }
    assertThat(messageCount()).isEqualTo(2);
  }

  @Test void chatIsAvailableWhilePlayingButInactiveParticipantsCannotSend() throws Exception {
    var sender = connect(student);
    var topic = subscribe(sender, chatTopic());
    var errors = subscribe(sender, "/user/queue/errors");
    room.startPlaying();
    rooms.saveAndFlush(room);
    send(sender, "퀴즈 중 질문");
    assertThat(take(topic).path("content").asText()).isEqualTo("퀴즈 중 질문");
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", studentParticipantId);
    send(sender, "퇴장 후 전송");
    assertThat(take(errors).path("code").asText()).isEqualTo("NOT_PARTICIPANT");
    assertThat(messageCount()).isEqualTo(1);
  }

  @Test void inactiveParticipantCannotSubscribeToNewChatMessages() throws Exception {
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", studentParticipantId);
    var formerParticipant = connect(student);
    var errors = subscribe(formerParticipant, "/user/queue/errors");
    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    formerParticipant.subscribe(chatTopic(), collect(received));
    // 같은 연결의 후속 구독 RECEIPT로 앞의 구독 시도가 처리됐음을 확인한다.
    subscribe(formerParticipant, "/user/queue/errors");
    service.send(room.getId(), owner.getId(), new SendChatRequest("참여자만 볼 수 있는 대화", null));
    assertThat(received.poll(1, TimeUnit.SECONDS)).as("비활성 참여자는 새 채팅을 수신하면 안 된다").isNull();
    assertThat(take(errors).path("code").asText()).isEqualTo("NOT_PARTICIPANT");
    jdbc.update("UPDATE room_participants SET left_at = NULL WHERE id = ?", studentParticipantId);
    var rejoined = subscribe(formerParticipant, chatTopic());
    service.send(room.getId(), owner.getId(), new SendChatRequest("재입장 후 대화", null));
    assertThat(take(rejoined).path("content").asText()).isEqualTo("재입장 후 대화");
    assertThat(received).as("거부됐던 구독은 재입장 후에도 등록되지 않아야 한다").isEmpty();
  }

  @Test void overflowingRoomIdReturnsErrorOnlyToSendingTabAndFollowingChatStillWorks() throws Exception {
    var sender = connect(student);
    var otherTab = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    var otherErrors = subscribe(otherTab, "/user/queue/errors");
    var topic = subscribe(sender, chatTopic());
    var headers = new StompHeaders();
    headers.setDestination("/app/rooms/9223372036854775808/chat");
    headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
    sender.send(headers, "{\"content\":\"잘못된 방 번호\"}".getBytes(StandardCharsets.UTF_8));
    assertThat(take(errors).path("code").asText()).isEqualTo("FORBIDDEN");
    assertThat(messageCount()).isZero();
    assertThat(otherErrors.poll(200, TimeUnit.MILLISECONDS)).isNull();
    send(sender, "같은 연결에서 정상 전송");
    assertThat(take(topic).path("content").asText()).isEqualTo("같은 연결에서 정상 전송");
    assertThat(messageCount()).isEqualTo(1);
  }

  @Test void professorInSomeoneElsesRoomRemainsAnonymousAndCannotReply() throws Exception {
    User guest = user(Role.PROFESSOR, "외부교수실명");
    Long guestId = join(room, guest, "비소유자닉네임");
    var sender = connect(guest);
    var errors = subscribe(sender, "/user/queue/errors");
    var topic = subscribe(sender, chatTopic());
    send(sender, "일반 참여자 전송");
    var received = take(topic);
    assertThat(received.path("nickname").asText()).isEqualTo("비소유자닉네임");
    assertThat(received.path("senderParticipantId").asLong()).isEqualTo(guestId);
    assertThat(received.path("isOwner").asBoolean()).isFalse();
    sendRaw(sender, "{\"content\":\"금지된 답글\",\"parentId\":" + received.path("id").asLong() + "}");
    assertThat(take(errors).path("code").asText()).isEqualTo("FORBIDDEN");
    assertThat(messageCount()).isEqualTo(1);
  }

  @Test void reconnectStateRestores50NonDeletedMessagesAndAllPinsOutsideWindow() throws Exception {
    Instant time = Instant.parse("2026-11-17T00:00:00Z");
    var stored = new ArrayList<ChatMessage>();
    for (int i = 0; i < 55; i++) {
      stored.add(messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "메시지" + i, null, time)));
    }
    for (int i : List.of(0, 1, 54)) {
      jdbc.update("INSERT INTO chat_pins VALUES (?, ?, ?, ?)", room.getId(), stored.get(i).getId(),
          owner.getId(), Timestamp.from(time.plusSeconds(i)));
    }
    jdbc.update("UPDATE chat_messages SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", stored.get(54).getId());
    // 참가자 퇴장 기록이 남아도 과거 작성자 닉네임은 복구한다.
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", studentParticipantId);
    var foreignRoom = room(owner);
    Long foreignSender = join(foreignRoom, owner, "다른방");
    messages.saveAndFlush(new ChatMessage(foreignRoom.getId(), foreignSender, "다른 방 비공개", null, time.plusSeconds(1)));
    var response = state(owner);
    assertThat(response.has("pinnedMessage")).isFalse();
    assertThat(response.path("recentMessages").size()).isEqualTo(50);
    assertThat(response.path("recentMessages").get(0).path("id").asLong()).isEqualTo(stored.get(53).getId());
    assertThat(response.path("recentMessages").get(49).path("id").asLong()).isEqualTo(stored.get(4).getId());
    assertThat(response.path("recentMessages").get(0).path("nickname").asText()).isEqualTo("익명학생");
    assertThat(response.path("pinnedMessages").size()).isEqualTo(2);
    assertThat(response.path("pinnedMessages").get(0).path("id").asLong()).isEqualTo(stored.get(0).getId());
    assertThat(response.path("pinnedMessages").get(1).path("id").asLong()).isEqualTo(stored.get(1).getId());
    assertThat(response.toString()).doesNotContain("다른 방 비공개", "메시지54", "학생실명");
  }

  @Test void ownerReplyRejectsDeletedAndForeignParentsAndRecoveryMasksDeletedParent() throws Exception {
    var parent = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "부모 비공개", null, Instant.now()));
    var sender = connect(owner);
    var errors = subscribe(sender, "/user/queue/errors");
    var topic = subscribe(sender, chatTopic());
    sendRaw(sender, "{\"content\":\"정상 답글\",\"parentId\":" + parent.getId() + "}");
    assertThat(take(topic).path("parentPreview").asText()).isEqualTo("부모 비공개");
    jdbc.update("UPDATE chat_messages SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", parent.getId());
    sendRaw(sender, "{\"content\":\"삭제 후 답글\",\"parentId\":" + parent.getId() + "}");
    assertThat(take(errors).path("code").asText()).isEqualTo("VALIDATION_FAILED");
    var foreignRoom = room(owner);
    Long foreignSender = join(foreignRoom, owner, "다른방");
    var foreignParent = messages.saveAndFlush(new ChatMessage(foreignRoom.getId(), foreignSender, "다른방 원문", null, Instant.now()));
    sendRaw(sender, "{\"content\":\"다른방 답글\",\"parentId\":" + foreignParent.getId() + "}");
    assertThat(take(errors).path("code").asText()).isEqualTo("VALIDATION_FAILED");
    var recent = state(owner).path("recentMessages");
    assertThat(recent.size()).isEqualTo(1);
    assertThat(recent.get(0).path("parentId").asLong()).isEqualTo(parent.getId());
    assertThat(recent.get(0).path("parentPreview").isNull()).isTrue();
    assertThat(recent.toString()).doesNotContain("부모 비공개");
  }

  @Test void commitPublicationHoldsRoomLockAndRollbackNeverPublishes() {
    service.send(room.getId(), student.getId(), new SendChatRequest("커밋 성공", null));
    assertThat(probe.committed).singleElement().satisfies(observation -> {
      assertThat(observation.visibleRows()).isEqualTo(1);
      assertThat(observation.roomLocked()).isTrue();
    });
    probe.rejectRoom = room.getId();
    assertThatIllegalStateException().isThrownBy(() ->
        service.send(room.getId(), student.getId(), new SendChatRequest("롤백", null)));
    assertThat(messageCount()).isEqualTo(1);
    assertThat(probe.committed).hasSize(1);
  }

  @Test void malformedUnicodeCannotBeStoredAsReplacementCharacters() {
    String malformed = String.valueOf(Character.MIN_HIGH_SURROGATE);
    assertThatThrownBy(() -> service.send(room.getId(), student.getId(), new SendChatRequest(malformed, null)))
        .isInstanceOfSatisfying(ChatException.class, error -> assertThat(error.code()).isEqualTo(ChatErrorCode.VALIDATION_FAILED));
    assertThat(messageCount()).isZero();
    assertThat(probe.committed).isEmpty();
  }

  @Test void escapedUnpairedSurrogatesReturnValidationErrorsWithoutDisconnecting() throws Exception {
    var sender = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    var topic = subscribe(sender, chatTopic());
    for (String escaped : List.of("\\uD800", "\\uDC00", "\\uD800x\\uDC00")) {
      sendRaw(sender, "{\"content\":\"" + escaped + "\"}");
      assertThat(take(errors).path("code").asText()).isEqualTo("VALIDATION_FAILED");
    }
    assertThat(messageCount()).isZero();
    assertThat(topic).isEmpty();
    send(sender, "정상😀");
    assertThat(take(topic).path("content").asText()).isEqualTo("정상😀");
    assertThat(messageCount()).isEqualTo(1);
  }

  @Test void roomCloseWaitsForCommittedChatPublicationAndThenRejectsFurtherMessages() throws Exception {
    var publishing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closingStarted = new CountDownLatch(1);
    List<String> published = new CopyOnWriteArrayList<>();
    String chat = chatTopic();
    String quiz = "/topic/rooms/" + room.getId() + "/quiz";
    ChannelInterceptor blocker = new ChannelInterceptor() {
      @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) return message;
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        if (!chat.equals(destination) && !quiz.equals(destination)) return message;
        if (chat.equals(destination)) {
          publishing.countDown();
          try {
            if (!release.await(7, TimeUnit.SECONDS)) throw new IllegalStateException("Chat publication was not released");
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chat publication interrupted", error);
          }
        }
        published.add(destination);
        return message;
      }
    };
    brokerChannel.addInterceptor(blocker);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var sending = executor.submit(() -> service.send(room.getId(), student.getId(), new SendChatRequest("종료 직전 채팅", null)));
      assertThat(publishing.await(7, TimeUnit.SECONDS)).isTrue();
      assertThat(messageCount()).as("발행 전에 DB 커밋이 끝나야 한다").isEqualTo(1);
      var closing = executor.submit(() -> {
        closingStarted.countDown();
        management.close(room.getId(), owner.getId());
      });
      assertThat(closingStarted.await(7, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> closing.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      assertThat(rooms.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(RoomStatus.OPEN);
      release.countDown();
      sending.get(7, TimeUnit.SECONDS);
      closing.get(7, TimeUnit.SECONDS);
      assertThat(published).containsExactly(chat, quiz);
      assertThat(rooms.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(RoomStatus.CLOSED);
      assertThatThrownBy(() -> service.send(room.getId(), student.getId(), new SendChatRequest("종료 후 채팅", null)))
          .isInstanceOfSatisfying(ChatException.class, error -> assertThat(error.code()).isEqualTo(ChatErrorCode.ROOM_CLOSED));
      assertThat(messageCount()).isEqualTo(1);
    } finally {
      release.countDown();
      executor.shutdownNow();
      brokerChannel.removeInterceptor(blocker);
      assertThat(executor.awaitTermination(7, TimeUnit.SECONDS)).isTrue();
    }
  }

  private User user(Role role, String name) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", name, null, role));
    createdUsers.add(user);
    return user;
  }

  private Room room(User host) {
    var codes = new JoinCodeGenerator();
    String code;
    do { code = codes.generate(); } while (rooms.existsByCode(code));
    var target = rooms.saveAndFlush(new Room(host.getId(), "채팅 검증", code));
    createdRooms.add(target);
    return target;
  }

  private Long join(Room target, User user, String nickname) {
    return participants.saveAndFlush(new RoomParticipant(target.getId(), user.getId(), nickname)).getId();
  }

  private long messageCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE room_id = ?", Long.class, room.getId());
  }

  private String chatTopic() { return "/topic/rooms/" + room.getId() + "/chat"; }

  private StompSession connect(User user) throws Exception {
    var headers = new StompHeaders();
    headers.add("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken());
    headers.setHeartbeat(new long[] {0, 0});
    var session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
        new StompSessionHandlerAdapter() {}).get(7, TimeUnit.SECONDS);
    sessions.add(session);
    return session;
  }

  private BlockingQueue<JsonNode> subscribe(StompSession session, String destination) throws Exception {
    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    var ready = new CompletableFuture<Void>();
    var headers = new StompHeaders();
    headers.setDestination(destination);
    headers.setReceipt(UUID.randomUUID().toString());
    session.subscribe(headers, collect(received)).addReceiptTask(() -> ready.complete(null));
    ready.get(7, TimeUnit.SECONDS);
    return received;
  }

  private StompFrameHandler collect(BlockingQueue<JsonNode> received) {
    return new StompFrameHandler() {
      @Override public Type getPayloadType(StompHeaders ignored) { return byte[].class; }
      @Override public void handleFrame(StompHeaders ignored, Object payload) {
        received.add(json.readTree((byte[]) payload));
      }
    };
  }

  private JsonNode take(BlockingQueue<JsonNode> queue) throws InterruptedException {
    var value = queue.poll(7, TimeUnit.SECONDS);
    assertThat(value).as("STOMP 응답 수신").isNotNull();
    return value;
  }

  private void send(StompSession session, String content) { sendRaw(session, json.writeValueAsString(new SendChatRequest(content, null))); }

  private void sendRaw(StompSession session, String body) {
    var headers = new StompHeaders();
    headers.setDestination("/app/rooms/" + room.getId() + "/chat");
    headers.setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_JSON);
    session.send(headers, body.getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode state(User user) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms/" + room.getId() + "/state"))
        .header("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken()).GET().build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }
}
