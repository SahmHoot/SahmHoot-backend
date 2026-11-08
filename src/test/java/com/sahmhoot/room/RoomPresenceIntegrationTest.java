package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.service.port.ParticipantPresencePort;
import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.dto.RoomSummaryResponse;
import com.sahmhoot.room.dto.message.OnlineCountMessage;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** #65 결과만 가짜로 제공하고 실제 참여자 DB·집계·이벤트·REST 연결을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomPresenceIntegrationTest {
  @Autowired RoomService creation;
  @Autowired RoomManagementService management;
  @MockitoSpyBean RoomParticipantRepository participants;
  @Autowired ParticipantPresencePort presence;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired JwtProvider jwt;
  @Autowired ApplicationEventPublisher events;
  @Autowired PlatformTransactionManager transactions;
  @Autowired RoomLockManager locks;
  @MockitoBean RoomPresencePort source;
  @MockitoBean RoomPresenceTaskScheduler scheduler;
  @MockitoBean SimpMessagingTemplate messaging;
  private final List<User> createdUsers = new ArrayList<>();
  private final List<Task> tasks = new ArrayList<>();
  private final AtomicReference<RoomPresenceSnapshot> current = new AtomicReference<>();
  private User owner;
  private User student;
  private User otherProfessor;
  private User left;
  private User outsider;
  private Long roomId;
  private Long studentId;
  private Long otherId;

  @BeforeEach void setup() {
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    otherProfessor = user(Role.PROFESSOR);
    left = user(Role.STUDENT);
    outsider = user(Role.STUDENT);
    roomId = creation.create(owner.getId(), new CreateRoomRequest("수업")).id();
    studentId = member(student, "학생");
    otherId = member(otherProfessor, "다른교수");
    Long leftId = member(left, "퇴장학생");
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", leftId);
    current.set(snapshot(Set.of()));
    when(source.getPresence(roomId)).thenAnswer(call -> current.get());
    when(scheduler.schedule(any(), any())).thenAnswer(call -> {
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      tasks.add(new Task(call.getArgument(1), future));
      return future;
    });
  }

  @AfterEach void cleanup() {
    management.close(roomId, owner.getId());
    jdbc.update("DELETE FROM room_participants WHERE room_id = ?", roomId);
    jdbc.update("DELETE FROM rooms WHERE id = ?", roomId);
    createdUsers.forEach(user -> users.deleteById(user.getId()));
  }

  @Test void realMembershipFilteringFeedsQuizPortMineAndStateWithSameCount() throws Exception {
    current.set(snapshot(Set.of(owner.getId(), student.getId(), otherProfessor.getId(), left.getId(), outsider.getId())));
    assertThat(presence.getOnlineParticipantIds(roomId)).containsExactlyInAnyOrder(studentId, otherId);
    mvc.perform(get("/api/rooms/mine").header("Authorization", token(owner)))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].onlineCount").value(2));
    stateCount(2);
    current.set(snapshot(Set.of(student.getId())));
    stateCount(1); // #65에서 한 탭이 남아 있는 동일 사용자 집합을 받았을 때도 1명이다.
    stateCount(1);
    current.set(snapshot(Set.of()));
    stateCount(0);
    assertThat(presence.getOnlineParticipantIds(roomId)).isEmpty();
  }

  @Test void manyChangesPublishLatestCountAfterReadTransactionEnds() {
    var before = snapshot(Set.of());
    current.set(snapshot(Set.of(student.getId())));
    events.publishEvent(new RoomPresenceChangedEvent(before, current.get(), Instant.now()));
    var previous = current.get();
    current.set(snapshot(Set.of(student.getId(), otherProfessor.getId())));
    events.publishEvent(new RoomPresenceChangedEvent(previous, current.get(), Instant.now()));
    assertThat(tasks).hasSize(1);
    verify(messaging, never()).convertAndSend(eq(topic()), any(OnlineCountMessage.class));
    doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(locks.isLocked(roomId)).isTrue();
      var message = call.getArgument(1, OnlineCountMessage.class);
      assertThat(message.type()).isEqualTo("ONLINE_COUNT");
      assertThat(message.onlineCount()).isEqualTo(2);
      assertThat(message.sentAt()).isNotNull();
      return null;
    }).when(messaging).convertAndSend(eq(topic()), any(OnlineCountMessage.class));
    tasks.removeFirst().action().run();
    verify(messaging).convertAndSend(eq(topic()), any(OnlineCountMessage.class));
  }

  @Test void mineBatchesRoomsAndDoesNotCountUsersConnectedOnlyToAnotherRoom() {
    Long otherRoom = creation.create(owner.getId(), new CreateRoomRequest("다른 수업")).id();
    try {
      participants.saveAndFlush(new RoomParticipant(otherRoom, student.getId(), "다른방학생"));
      participants.saveAndFlush(new RoomParticipant(otherRoom, otherProfessor.getId(), "다른교수"));
      Long leftId = participants.saveAndFlush(new RoomParticipant(otherRoom, left.getId(), "퇴장학생")).getId();
      jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", leftId);
      current.set(snapshot(Set.of(owner.getId(), student.getId())));
      when(source.getPresence(otherRoom)).thenReturn(new RoomPresenceSnapshot(otherRoom,
          Set.of(owner.getId(), otherProfessor.getId(), left.getId(), outsider.getId()), true));

      var mine = management.mine(owner.getId());
      assertThat(mine).extracting(RoomSummaryResponse::id).containsExactly(otherRoom, roomId);
      assertThat(mine).extracting(RoomSummaryResponse::onlineCount).containsExactly(1, 1);
      verify(participants, times(1)).findByRoomIdInAndUserIdInAndLeftAtIsNull(eq(Set.of(roomId, otherRoom)), anySet());
      verify(participants, never()).findByRoomIdAndUserIdInAndLeftAtIsNull(anyLong(), anySet());
    } finally {
      management.close(otherRoom, owner.getId());
      jdbc.update("DELETE FROM room_participants WHERE room_id = ?", otherRoom);
      jdbc.update("DELETE FROM rooms WHERE id = ?", otherRoom);
    }
  }

  @Test void ownerOnlyConnectionChangeDoesNotPublishStudentCount() {
    var before = snapshot(Set.of());
    current.set(snapshot(Set.of(owner.getId())));
    events.publishEvent(new RoomPresenceChangedEvent(before, current.get(), Instant.now()));
    assertThat(tasks).isEmpty();
    verify(messaging, never()).convertAndSend(eq(topic()), any(OnlineCountMessage.class));
  }

  @Test void rollbackDropsPresenceEventAndCommitSchedulesItAfterCommit() {
    participants.deleteById(studentId);
    current.set(snapshot(Set.of(student.getId())));
    var change = new RoomPresenceChangedEvent(snapshot(Set.of()), current.get(), Instant.now());
    var tx = new TransactionTemplate(transactions);
    tx.execute(status -> {
      member(student, "학생");
      events.publishEvent(change);
      assertThat(tasks).isEmpty();
      status.setRollbackOnly();
      return null;
    });
    assertThat(tasks).isEmpty();
    assertThat(presence.getOnlineParticipantIds(roomId)).isEmpty();
    tx.execute(status -> {
      member(student, "학생");
      events.publishEvent(change);
      assertThat(tasks).isEmpty();
      return null;
    });
    assertThat(tasks).hasSize(1);
    tasks.removeFirst().action().run();
    verify(messaging).convertAndSend(eq(topic()), any(OnlineCountMessage.class));
  }

  @Test void roomCloseCancelsPendingAndLateCallbackCannotPublish() {
    current.set(snapshot(Set.of(student.getId())));
    events.publishEvent(new RoomPresenceChangedEvent(snapshot(Set.of()), current.get(), Instant.now()));
    var pending = tasks.removeFirst();
    management.close(roomId, owner.getId());
    verify(pending.future()).cancel(false);
    pending.action().run();
    verify(messaging, never()).convertAndSend(eq(topic()), any(OnlineCountMessage.class));
    assertThatThrownBy(() -> presence.getOnlineParticipantIds(roomId)).isInstanceOfSatisfying(RoomException.class,
        error -> assertThat(error.getErrorCode()).isEqualTo(RoomErrorCode.ROOM_CLOSED));
  }

  private void stateCount(int count) throws Exception {
    mvc.perform(get("/api/rooms/" + roomId + "/state").header("Authorization", token(student)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.onlineCount").value(count));
  }

  private RoomPresenceSnapshot snapshot(Set<Long> ids) {
    return new RoomPresenceSnapshot(roomId, ids, ids.contains(owner.getId()));
  }

  private User user(Role role) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only", "사용자", null, role));
    createdUsers.add(user);
    return user;
  }

  private Long member(User user, String nickname) {
    return participants.saveAndFlush(new RoomParticipant(roomId, user.getId(), nickname)).getId();
  }

  private String topic() { return "/topic/rooms/" + roomId + "/presence"; }
  private String token(User user) { return "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken(); }
  private record Task(Runnable action, ScheduledFuture<?> future) {}
}
