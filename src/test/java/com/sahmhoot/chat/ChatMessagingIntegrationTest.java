package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.chat.dto.SendChatRequest;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.event.ChatMessageEvent;
import com.sahmhoot.chat.event.ChatStatusChangedEvent;
import com.sahmhoot.chat.event.NoticePinnedEvent;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.repository.ChatPinRepository;
import com.sahmhoot.chat.service.ChatModerationService;
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
import jakarta.persistence.EntityManager;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    @Bean CommitProbe chatCommitProbe(DataSource source, RoomLockManager locks, EntityManager entityManager) {
      return new CommitProbe(source, locks, entityManager);
    }
  }

  static class CommitProbe {
    final DataSource source;
    final RoomLockManager locks;
    final List<Observation> committed = new CopyOnWriteArrayList<>();
    final EntityManager entityManager;
    volatile Long rejectRoom;
    volatile Long rejectRestrictionRoom;
    volatile Long rejectPinCommitRoom;

    CommitProbe(DataSource source, RoomLockManager locks, EntityManager entityManager) {
      this.source = source;
      this.locks = locks;
      this.entityManager = entityManager;
    }

    @EventListener
    public void rejectRestriction(ChatStatusChangedEvent event) {
      if (event.roomId().equals(rejectRestrictionRoom)) {
        entityManager.flush();
        entityManager.createNativeQuery("""
            INSERT INTO chat_restrictions (room_id, participant_id, restricted_by_user_id, restricted_at)
            VALUES (:roomId, -1, :userId, UTC_TIMESTAMP(6))
            """).setParameter("roomId", event.roomId()).setParameter("userId", event.userId()).executeUpdate();
      }
    }

    @EventListener
    public void rejectPinCommit(NoticePinnedEvent event) {
      if (!event.roomId().equals(rejectPinCommitRoom)) return;
      // 리스너 실행 순서와 무관하게 모든 리스너가 돈 뒤 커밋 단계에서 실패시킨다.
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void beforeCommit(boolean readOnly) {
          throw new IllegalStateException("Force rollback at commit");
        }
      });
    }

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
  @MockitoSpyBean ChatPinRepository pins;
  @Autowired EntityManager entityManager;
  @Autowired ChatService service;
  @Autowired ChatModerationService moderation;
  @Autowired RoomManagementService management;
  @Autowired JwtProvider jwt;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper json;
  @Autowired CommitProbe probe;
  @Autowired @Qualifier("brokerChannel") AbstractSubscribableChannel brokerChannel;
  private final HttpClient http = HttpClient.newHttpClient();
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
    probe.rejectRestrictionRoom = null;
    probe.rejectPinCommitRoom = null;
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

  @Test void onlyTheOwnerCanManageChatAndTargetsMustBelongToThatRoom() throws Exception {
    var professor = user(Role.PROFESSOR, "다른 교수");
    var otherRoom = room(professor);
    join(room, professor, "다른 참가자");
    var otherParticipant = join(otherRoom, student, "다른 방 학생");
    var foreign = messages.saveAndFlush(new ChatMessage(otherRoom.getId(), otherParticipant, "다른 방 질문",
        null, Instant.now()));
    var here = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "이 방 질문",
        null, Instant.now()));

    var authorUrl = URI.create("http://localhost:" + port + "/api/rooms/" + room.getId()
        + "/messages/" + here.getId() + "/author");
    var unauthenticated = http.send(HttpRequest.newBuilder(authorUrl).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(unauthenticated.statusCode()).isEqualTo(401);

    for (User nonOwner : List.of(student, professor)) {
      for (var request : List.of(
          List.of("GET", "/messages/" + here.getId() + "/author"),
          List.of("GET", "/messages/-1/author"),
          List.of("DELETE", "/messages/" + here.getId()),
          List.of("DELETE", "/messages/-1"))) {
        assertModerationError(nonOwner, request.getFirst(), request.getLast(), 403, "FORBIDDEN");
      }
      for (long target : List.of(studentParticipantId, -1L)) {
        assertModerationError(changeRestriction(nonOwner, target, true), 403, "FORBIDDEN");
        assertModerationError(changeRestriction(nonOwner, target, false), 403, "FORBIDDEN");
      }
    }
    assertModerationError(changeRestriction(owner, ownerParticipantId, true), 400, "CANNOT_BLOCK_OWNER");
    for (long unavailable : List.of(otherParticipant, -1L)) {
      assertModerationError(changeRestriction(owner, unavailable, true), 404, "PARTICIPANT_NOT_FOUND");
      assertModerationError(changeRestriction(owner, unavailable, false), 404, "PARTICIPANT_NOT_FOUND");
    }
    for (long unavailable : List.of(foreign.getId(), -1L)) {
      assertModerationError(owner, "GET", "/messages/" + unavailable + "/author", 404, "MESSAGE_NOT_FOUND");
      assertModerationError(owner, "DELETE", "/messages/" + unavailable, 404, "MESSAGE_NOT_FOUND");
    }
    assertThat(restrictionCount(room, studentParticipantId)).isZero();
    assertThat(messages.findById(here.getId()).orElseThrow().getDeletedAt()).isNull();
    assertThat(messages.findById(foreign.getId()).orElseThrow().getDeletedAt()).isNull();
  }

  @Test void restrictionChangesReachBothTabsAndControlActualSendingOnlyInTheirRoom() throws Exception {
    var observer = user(Role.STUDENT, "관찰 학생");
    join(room, observer, "익명 관찰자");
    var otherRoom = room(owner);
    var otherParticipant = join(otherRoom, student, "다른 방 닉네임");
    jdbc.update("INSERT INTO chat_restrictions VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
        otherRoom.getId(), otherParticipant, owner.getId());
    var one = connect(student);
    var two = connect(student);
    var oneStatus = subscribe(one, personalTopic());
    var twoStatus = subscribe(two, personalTopic());
    var observerStatus = subscribe(connect(observer), personalTopic());
    var otherRoomStatus = subscribe(one, "/user/queue/rooms/" + otherRoom.getId() + "/me");
    var errors = subscribe(one, "/user/queue/errors");
    var broadcast = subscribe(one, chatTopic());
    assertRestrictionResponse(changeRestriction(owner, studentParticipantId, true), true);
    var restricted = take(oneStatus);
    assertThat(take(twoStatus)).isEqualTo(restricted);
    assertThat(restricted.path("type").asText()).isEqualTo("MY_STATUS");
    assertThat(restricted.path("chatBlocked").asBoolean()).isTrue();
    assertThat(restricted.size()).isEqualTo(3);
    assertThat(restrictionCount(room, studentParticipantId)).isEqualTo(1);
    assertThat(state(student).path("me").path("chatBlocked").asBoolean()).isTrue();

    assertRestrictionResponse(changeRestriction(owner, studentParticipantId, true), true);
    send(one, "전송이 제한된 메시지");
    assertThat(take(errors).path("code").asText()).isEqualTo("CHAT_BLOCKED");
    assertThat(broadcast.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(messageCount()).isZero();

    assertRestrictionResponse(changeRestriction(owner, studentParticipantId, false), false);
    var released = take(oneStatus);
    assertThat(take(twoStatus)).isEqualTo(released);
    assertThat(released.path("type").asText()).isEqualTo("MY_STATUS");
    assertThat(released.path("chatBlocked").asBoolean()).isFalse();
    assertRestrictionResponse(changeRestriction(owner, studentParticipantId, false), false);
    assertThat(restrictionCount(room, studentParticipantId)).isZero();
    assertThat(restrictionCount(otherRoom, otherParticipant)).isEqualTo(1);
    assertThat(state(student).path("me").path("chatBlocked").asBoolean()).isFalse();
    send(two, "제한 해제 후 정상 전송");
    assertThat(take(broadcast).path("type").asText()).isEqualTo("MESSAGE");
    assertThat(messageCount()).isEqualTo(1);
    assertThat(oneStatus.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(twoStatus).isEmpty();
    assertThat(observerStatus).isEmpty();
    assertThat(otherRoomStatus).isEmpty();
  }

  @Test void deleteKeepsAuthorAndReplyLinksAndRemovesOnlyItsPinBeforeBroadcast() throws Exception {
    var message = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "삭제된 질문",
        null, Instant.now()));
    var reply = messages.saveAndFlush(new ChatMessage(room.getId(), ownerParticipantId, "남은 답글",
        message.getId(), Instant.now()));
    pin(message.getId());
    pin(reply.getId());
    var studentTopic = subscribe(connect(student), chatTopic());
    var ownerTopic = subscribe(connect(owner), chatTopic());
    String path = "/messages/" + message.getId();

    var committedAtPublication = new CompletableFuture<Boolean>();
    var observer = new ChannelInterceptor() {
      @Override public Message<?> preSend(Message<?> frame, MessageChannel channel) {
        if (chatTopic().equals(SimpMessageHeaderAccessor.getDestination(frame.getHeaders()))) {
          try {
            committedAtPublication.complete(isDeletionCommitted(message.getId()));
          } catch (SQLException error) {
            committedAtPublication.completeExceptionally(error);
          }
        }
        return frame;
      }
    };
    brokerChannel.addInterceptor(observer);
    try {
      assertThat(moderate(owner, "DELETE", path).statusCode()).isEqualTo(204);
      assertThat(committedAtPublication.get(7, TimeUnit.SECONDS)).isTrue();
    } finally {
      brokerChannel.removeInterceptor(observer);
    }
    var deletedEvent = take(studentTopic);
    assertThat(take(ownerTopic)).isEqualTo(deletedEvent);
    assertThat(deletedEvent.path("type").asText()).isEqualTo("MESSAGE_DELETED");
    assertThat(deletedEvent.path("messageId").asLong()).isEqualTo(message.getId());
    assertThat(deletedEvent.size()).isEqualTo(3);
    var unpinnedEvent = take(studentTopic);
    assertThat(take(ownerTopic)).isEqualTo(unpinnedEvent);
    assertThat(unpinnedEvent.path("type").asText()).isEqualTo("NOTICE_UNPINNED");
    assertThat(unpinnedEvent.path("messageId").asLong()).isEqualTo(message.getId());
    assertThat(unpinnedEvent.path("sentAt")).isEqualTo(deletedEvent.path("sentAt"));
    var deleted = messages.findById(message.getId()).orElseThrow();
    assertThat(deleted.getDeletedAt()).isNotNull();
    assertThat(deleted.getSenderParticipantId()).isEqualTo(studentParticipantId);
    assertThat(deleted.getContent()).isEqualTo("삭제된 질문");
    assertThat(messages.findById(reply.getId()).orElseThrow().getParentId()).isEqualTo(message.getId());
    assertThat(pinnedIds()).containsExactly(reply.getId());

    assertThat(moderate(owner, "DELETE", path).statusCode()).isEqualTo(204);
    assertThat(messages.findById(message.getId()).orElseThrow().getDeletedAt()).isEqualTo(deleted.getDeletedAt());
    assertThat(studentTopic.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(ownerTopic).isEmpty();

    var response = moderate(owner, "GET", path + "/author");
    assertThat(response.statusCode()).isEqualTo(200);
    var author = json.readTree(response.body());
    assertThat(author.path("participantId").asLong()).isEqualTo(studentParticipantId);
    assertThat(author.path("nickname").asText()).isEqualTo("익명학생");
    assertThat(author.path("email").asText()).isEqualTo(student.getEmail().substring(0, 3) + "***@test.invalid");
    assertThat(response.body()).doesNotContain(student.getEmail());
    assertModerationError(student, "GET", path + "/author", 403, "FORBIDDEN");
    var snapshot = state(student);
    for (String list : List.of("recentMessages", "pinnedMessages")) {
      assertThat(snapshot.path(list).size()).isEqualTo(1);
      var visible = snapshot.path(list).get(0);
      assertThat(visible.path("id").asLong()).isEqualTo(reply.getId());
      assertThat(visible.path("parentPreview").isNull()).isTrue();
      assertThat(visible.has("email")).isFalse();
    }
  }

  @Test void ownerPinsSeveralMessagesThatEveryoneReceivesAndReconnectRestoresInPinOrder() throws Exception {
    var question = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "시험 범위요?",
        null, Instant.now()));
    var answer = messages.saveAndFlush(new ChatMessage(room.getId(), ownerParticipantId, "3장까지",
        question.getId(), Instant.now()));
    var studentTopic = subscribe(connect(student), chatTopic());
    var ownerTopic = subscribe(connect(owner), chatTopic());

    var pinned = pinRequest(owner, "PUT", answer.getId());
    assertThat(pinned.statusCode()).isEqualTo(200);
    var body = json.readTree(pinned.body());
    assertThat(body.path("id").asLong()).isEqualTo(answer.getId());
    assertThat(body.path("isOwner").asBoolean()).isTrue();
    assertThat(body.path("parentPreview").asText()).isEqualTo("시험 범위요?");
    assertThat(body.has("email")).isFalse();
    var event = take(studentTopic);
    assertThat(take(ownerTopic)).isEqualTo(event);
    assertThat(event.path("type").asText()).isEqualTo("NOTICE_PINNED");
    assertThat(event.path("message")).isEqualTo(body);
    assertThat(event.size()).isEqualTo(3);

    assertThat(pinRequest(owner, "PUT", question.getId()).statusCode()).isEqualTo(200);
    assertThat(take(studentTopic).path("message").path("id").asLong()).isEqualTo(question.getId());
    take(ownerTopic);
    // 이미 고정된 메시지는 다시 발행하지 않고 처음 고정한 순서를 유지한다.
    assertThat(pinRequest(owner, "PUT", answer.getId()).statusCode()).isEqualTo(200);
    assertThat(studentTopic.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(pinnedIdsOf(state(student))).containsExactly(answer.getId(), question.getId());
    assertThat(pinnedIdsOf(state(owner))).containsExactly(answer.getId(), question.getId());

    assertThat(pinRequest(owner, "DELETE", answer.getId()).statusCode()).isEqualTo(204);
    var unpinned = take(studentTopic);
    assertThat(take(ownerTopic)).isEqualTo(unpinned);
    assertThat(unpinned.path("type").asText()).isEqualTo("NOTICE_UNPINNED");
    assertThat(unpinned.path("messageId").asLong()).isEqualTo(answer.getId());
    assertThat(unpinned.size()).isEqualTo(3);
    assertThat(pinRequest(owner, "DELETE", answer.getId()).statusCode()).isEqualTo(204);
    assertThat(studentTopic.poll(250, TimeUnit.MILLISECONDS)).isNull();
    assertThat(pinnedIdsOf(state(student))).containsExactly(question.getId());
    assertThat(messages.findById(answer.getId()).orElseThrow().getDeletedAt()).isNull();
  }

  @Test void pinThatFailsAtCommitIsRolledBackAndNeverBroadcast() throws Exception {
    var message = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "고정 실패",
        null, Instant.now()));
    var received = subscribe(connect(student), chatTopic());
    probe.rejectPinCommitRoom = room.getId();

    assertPinError(owner, "PUT", message.getId(), 500, "INTERNAL_ERROR");
    assertThat(pinnedIds()).isEmpty();
    assertThat(received.poll(250, TimeUnit.MILLISECONDS)).isNull();

    probe.rejectPinCommitRoom = null;
    assertThat(pinRequest(owner, "PUT", message.getId()).statusCode()).isEqualTo(200);
    assertThat(take(received).path("type").asText()).isEqualTo("NOTICE_PINNED");
    assertThat(pinnedIds()).containsExactly(message.getId());
  }

  @Test void onlyTheOwnerOfAnOpenRoomCanPinUndeletedMessagesOfThatRoom() throws Exception {
    var professor = user(Role.PROFESSOR, "다른 교수");
    var otherRoom = room(professor);
    join(room, professor, "다른 참가자");
    var foreign = messages.saveAndFlush(new ChatMessage(otherRoom.getId(), join(otherRoom, student, "다른 방 학생"),
        "다른 방 질문", null, Instant.now()));
    var here = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "이 방 질문",
        null, Instant.now()));
    var deleted = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "지운 질문",
        null, Instant.now()));
    jdbc.update("UPDATE chat_messages SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", deleted.getId());
    var received = subscribe(connect(student), chatTopic());

    for (User nonOwner : List.of(student, professor)) {
      assertPinError(nonOwner, "PUT", here.getId(), 403, "FORBIDDEN");
      assertPinError(nonOwner, "DELETE", here.getId(), 403, "FORBIDDEN");
    }
    assertPinError(owner, "PUT", foreign.getId(), 404, "MESSAGE_NOT_FOUND");
    assertPinError(owner, "PUT", -1L, 404, "MESSAGE_NOT_FOUND");
    assertPinError(owner, "PUT", deleted.getId(), 400, "MESSAGE_DELETED");
    assertThat(pinnedIds()).isEmpty();

    pin(here.getId());
    management.close(room.getId(), owner.getId());
    assertPinError(owner, "PUT", here.getId(), 409, "ROOM_CLOSED");
    assertPinError(owner, "DELETE", here.getId(), 409, "ROOM_CLOSED");
    assertThat(pinnedIds()).containsExactly(here.getId());
    assertThat(received.poll(250, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test void databaseFailureDuringPinRemovalRollsBackTheDeleteAndSuppressesBroadcast() throws Exception {
    var message = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "유지할 질문",
        null, Instant.now()));
    pin(message.getId());
    var received = subscribe(connect(student), chatTopic());
    doAnswer(invocation -> {
      entityManager.flush();
      jdbc.update("DELETE FROM chat_pins WHERE room_id = ? AND message_id = ?", room.getId(), message.getId());
      // MySQL에서 존재하지 않는 메시지의 고정 FK를 거부한다.
      pin(-1L);
      return null;
    }).when(pins).removePin(room.getId(), message.getId());

    assertModerationError(owner, "DELETE", "/messages/" + message.getId(), 500, "INTERNAL_ERROR");
    assertThat(messages.findById(message.getId()).orElseThrow().getDeletedAt()).isNull();
    assertThat(pinnedIds()).containsExactly(message.getId());
    assertThat(received.poll(250, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test void databaseFailureAfterFlushingRestrictionDoesNotPublishMyStatus() throws Exception {
    var status = subscribe(connect(student), personalTopic());
    probe.rejectRestrictionRoom = room.getId();

    assertModerationError(changeRestriction(owner, studentParticipantId, true), 500, "INTERNAL_ERROR");
    assertThat(restrictionCount(room, studentParticipantId)).isZero();
    assertThat(status.poll(250, TimeUnit.MILLISECONDS)).isNull();
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


  @Test void restrictionWaitsForInFlightSendAndBlocksTheNextMessage() throws Exception {
    var sender = connect(student);
    var errors = subscribe(sender, "/user/queue/errors");
    var topic = subscribe(sender, chatTopic());
    var restrictionStarted = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2); var gate = new ChatPublicationGate()) {
      var sending = executor.submit(() ->
          service.send(room.getId(), student.getId(), new SendChatRequest("제한 직전 전송", null)));
      assertThat(gate.publishing.await(7, TimeUnit.SECONDS)).isTrue();
      var restricting = executor.submit(() -> {
        restrictionStarted.countDown();
        moderation.updateRestriction(room.getId(), owner.getId(), studentParticipantId, true);
      });
      assertThat(restrictionStarted.await(7, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> restricting.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      assertThat(restrictionCount(room, studentParticipantId)).isZero();

      gate.release.countDown();
      sending.get(7, TimeUnit.SECONDS);
      restricting.get(7, TimeUnit.SECONDS);
      assertThat(take(topic).path("content").asText()).isEqualTo("제한 직전 전송");
      assertThat(restrictionCount(room, studentParticipantId)).isEqualTo(1);

      send(sender, "제한 이후 전송");
      assertThat(take(errors).path("code").asText()).isEqualTo("CHAT_BLOCKED");
      assertThat(messageCount()).isEqualTo(1);
      assertThat(gate.publications.get()).isEqualTo(1);
    }
  }

  @Test void concurrentDeletesPublishOnceAndPreserveTheFirstDeletionTime() throws Exception {
    var message = messages.saveAndFlush(new ChatMessage(room.getId(), studentParticipantId, "삭제 대상",
        null, Instant.now()));
    pin(message.getId());
    var topic = subscribe(connect(student), chatTopic());
    var secondStarted = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2); var gate = new ChatPublicationGate()) {
      var first = executor.submit(() -> moderation.delete(room.getId(), owner.getId(), message.getId()));
      assertThat(gate.publishing.await(7, TimeUnit.SECONDS)).isTrue();
      var firstDeletedAt = messages.findById(message.getId()).orElseThrow().getDeletedAt();
      assertThat(firstDeletedAt).isNotNull();

      var second = executor.submit(() -> {
        secondStarted.countDown();
        moderation.delete(room.getId(), owner.getId(), message.getId());
      });
      assertThat(secondStarted.await(7, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

      gate.release.countDown();
      first.get(7, TimeUnit.SECONDS);
      second.get(7, TimeUnit.SECONDS);
      var event = take(topic);
      assertThat(event.path("type").asText()).isEqualTo("MESSAGE_DELETED");
      assertThat(event.path("messageId").asLong()).isEqualTo(message.getId());
      assertThat(take(topic).path("type").asText()).isEqualTo("NOTICE_UNPINNED");
      assertThat(topic.poll(250, TimeUnit.MILLISECONDS)).isNull();
      // 첫 삭제의 MESSAGE_DELETED·NOTICE_UNPINNED만 나가고 두 번째 삭제는 아무것도 보내지 않는다.
      assertThat(gate.publications.get()).isEqualTo(2);
      assertThat(messages.findById(message.getId()).orElseThrow().getDeletedAt()).isEqualTo(firstDeletedAt);
      assertThat(pinnedIds()).isEmpty();
    }
  }

  private final class ChatPublicationGate implements ChannelInterceptor, AutoCloseable {
    private final CountDownLatch publishing = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger publications = new AtomicInteger();

    private ChatPublicationGate() {
      brokerChannel.addInterceptor(this);
    }

    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
      if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE
          || !chatTopic().equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))) return message;
      if (publications.incrementAndGet() == 1) {
        publishing.countDown();
        try {
          if (!release.await(7, TimeUnit.SECONDS)) throw new IllegalStateException("Chat publication was not released");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Chat publication interrupted", error);
        }
      }
      return message;
    }

    @Override public void close() {
      release.countDown();
      brokerChannel.removeInterceptor(this);
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

  private boolean isDeletionCommitted(Long messageId) throws SQLException {
    // 원래 트랜잭션과 연결을 공유하지 않아 미커밋 갱신은 보이지 않는다.
    try (var connection = probe.source.getConnection();
        var query = connection.prepareStatement("""
            SELECT m.deleted_at IS NOT NULL AND p.message_id IS NULL
            FROM chat_messages m LEFT JOIN chat_pins p ON p.room_id = m.room_id AND p.message_id = m.id
            WHERE m.room_id = ? AND m.id = ?
            """)) {
      query.setLong(1, room.getId());
      query.setLong(2, messageId);
      try (var result = query.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  private String personalTopic() { return "/user/queue/rooms/" + room.getId() + "/me"; }

  private long restrictionCount(Room target, Long participantId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM chat_restrictions WHERE room_id = ? AND participant_id = ?",
        Long.class, target.getId(), participantId);
  }

  private void pin(Long messageId) {
    jdbc.update("INSERT INTO chat_pins VALUES (?, ?, ?, UTC_TIMESTAMP(6))",
        room.getId(), messageId, owner.getId());
  }

  private List<Long> pinnedIds() {
    return jdbc.queryForList("SELECT message_id FROM chat_pins WHERE room_id = ?", Long.class, room.getId());
  }

  private HttpResponse<String> moderate(User actor, String method, String path) throws Exception {
    var request = moderationRequest(actor, path).method(method, HttpRequest.BodyPublishers.noBody()).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> changeRestriction(User actor, long target, boolean blocked) throws Exception {
    var request = moderationRequest(actor, "/participants/" + target).header("Content-Type", "application/json")
        .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"chatBlocked\":" + blocked + "}")).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder moderationRequest(User actor, String path) {
    var url = URI.create("http://localhost:" + port + "/api/rooms/" + room.getId() + path);
    return HttpRequest.newBuilder(url)
        .header("Authorization", "Bearer " + jwt.issue(actor.getId(), actor.getRole()).accessToken());
  }

  private void assertRestrictionResponse(HttpResponse<String> response, boolean blocked) {
    assertThat(response.statusCode()).isEqualTo(200);
    var body = json.readTree(response.body());
    assertThat(body.path("participantId").asLong()).isEqualTo(studentParticipantId);
    assertThat(body.path("nickname").asText()).isEqualTo("익명학생");
    assertThat(body.path("chatBlocked").isBoolean()).isTrue();
    assertThat(body.path("chatBlocked").asBoolean()).isEqualTo(blocked);
  }

  private void assertModerationError(User actor, String method, String path, int status, String code)
      throws Exception {
    assertModerationError(moderate(actor, method, path), status, code);
  }

  private void assertModerationError(HttpResponse<String> response, int status, String code) {
    assertThat(response.statusCode()).isEqualTo(status);
    var body = json.readTree(response.body());
    assertThat(body.path("code").asText()).isEqualTo(code);
    assertThat(body.has("email")).isFalse();
  }

  private HttpResponse<String> pinRequest(User actor, String method, Long messageId) throws Exception {
    var url = URI.create("http://localhost:" + port + "/api/rooms/" + room.getId() + "/pinned-messages/" + messageId);
    var request = HttpRequest.newBuilder(url)
        .header("Authorization", "Bearer " + jwt.issue(actor.getId(), actor.getRole()).accessToken())
        .method(method, HttpRequest.BodyPublishers.noBody()).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private void assertPinError(User actor, String method, Long messageId, int status, String code) throws Exception {
    var response = pinRequest(actor, method, messageId);
    assertThat(response.statusCode()).isEqualTo(status);
    assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
  }

  private List<Long> pinnedIdsOf(JsonNode snapshot) {
    var ids = new ArrayList<Long>();
    snapshot.path("pinnedMessages").forEach(message -> ids.add(message.path("id").asLong()));
    return ids;
  }

  private JsonNode state(User user) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rooms/" + room.getId() + "/state"))
        .header("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken()).GET().build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }
}
