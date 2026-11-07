package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.RoomStateResponse.Phase;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.JoinCodeGenerator;
import com.sahmhoot.room.service.port.RoomQuizStatePort;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomStateIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired RoomParticipantRepository participants;
  @Autowired RoomLockManager locks;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean RoomQuizStatePort quizzes;
  private final List<Long> userIds = new ArrayList<>();
  private User owner;
  private User student;
  private Room room;
  private Long studentParticipantId;

  @BeforeEach void setup() {
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    var codes = new JoinCodeGenerator();
    String code;
    do { code = codes.generate(); } while (rooms.existsByCode(code));
    room = rooms.saveAndFlush(new Room(owner.getId(), "자료구조", code));
    participants.saveAndFlush(new RoomParticipant(room.getId(), owner.getId(), "담당교수"));
    studentParticipantId = participants.saveAndFlush(
        new RoomParticipant(room.getId(), student.getId(), "익명학생")).getId();
    when(quizzes.read(anyLong(), anyLong())).thenReturn(RoomQuizStatePort.Snapshot.waiting());
  }

  @AfterEach void cleanup() {
    if (room != null) {
      jdbc.update("DELETE FROM room_participants WHERE room_id = ?", room.getId());
      rooms.deleteById(room.getId());
    }
    userIds.forEach(users::deleteById);
  }

  @Test void waitingStateUsesAuthenticatedParticipantAndMatchesSpec() throws Exception {
    state(student).andExpect(status().isOk())
        .andExpect(jsonPath("$.serverTime").isString())
        .andExpect(jsonPath("$.room.length()").value(6))
        .andExpect(jsonPath("$.room.id").value(room.getId()))
        .andExpect(jsonPath("$.room.status").value("OPEN"))
        .andExpect(jsonPath("$.room.joinCode").value(room.getCode()))
        .andExpect(jsonPath("$.room.joinUrl").value("http://localhost:5173/join/" + room.getCode()))
        .andExpect(jsonPath("$.room.ownerName").value(owner.getName()))
        .andExpect(jsonPath("$.me.participantId").value(studentParticipantId))
        .andExpect(jsonPath("$.me.nickname").value("익명학생"))
        .andExpect(jsonPath("$.me.isOwner").value(false))
        .andExpect(jsonPath("$.me.chatBlocked").value(false))
        .andExpect(jsonPath("$.onlineCount").value(0))
        .andExpect(jsonPath("$.quiz").value(nullValue()))
        .andExpect(jsonPath("$.pinnedMessage").value(nullValue()))
        .andExpect(jsonPath("$.recentMessages").isEmpty())
        .andExpect(jsonPath("$.progress").doesNotExist())
        .andExpect(jsonPath("$.reactionStats").doesNotExist())
        .andExpect(jsonPath("$.me.email").doesNotExist());
    verify(quizzes).read(room.getId(), studentParticipantId);
    state(owner).andExpect(status().isOk())
        .andExpect(jsonPath("$.me.isOwner").value(true))
        .andExpect(jsonPath("$.reactionStats").isMap());
  }

  @ParameterizedTest @EnumSource(Phase.class)
  void phaseResponseHidesPrematureAnswersDistributionsAndOwnerOnlyFields(Phase phase) throws Exception {
    room.startPlaying();
    rooms.saveAndFlush(room);
    when(quizzes.read(anyLong(), anyLong())).thenReturn(RoomStateServiceTest.snapshot(phase));
    var studentResponse = state(student).andExpect(status().isOk())
        .andExpect(jsonPath("$.quizRun.status").value(phase == Phase.RESULT ? "FINISHED" : "RUNNING"))
        .andExpect(jsonPath("$.quiz.phase").value(phase.name()))
        .andExpect(jsonPath("$.quiz.myAnswers[0].choiceId").value(3001))
        .andExpect(jsonPath("$.progress").doesNotExist())
        .andExpect(jsonPath("$.reactionStats").doesNotExist());
    var ownerResponse = state(owner).andExpect(status().isOk())
        .andExpect(jsonPath("$.quiz.myAnswers").doesNotExist());
    if (phase == Phase.RESULT) {
      studentResponse.andExpect(jsonPath("$.quiz.result.questions[0].choices[0].count").value(1));
      ownerResponse.andExpect(jsonPath("$.quiz.result.questions[0].correctChoiceId").value(3001));
    } else {
      if (phase == Phase.FEEDBACK) {
        studentResponse.andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(3001));
      } else {
        studentResponse.andExpect(jsonPath("$.quiz.currentQuestion.correctChoiceId").value(nullValue()));
      }
      studentResponse.andExpect(jsonPath("$.quiz.result").value(nullValue()))
          .andExpect(jsonPath("$.quiz.currentQuestion.choices[0].isCorrect").doesNotExist())
          .andExpect(jsonPath("$.quiz.currentQuestion.choices[0].count").doesNotExist());
      ownerResponse.andExpect(jsonPath("$.quiz.result").value(nullValue()));
    }
    if (phase == Phase.QUESTION) {
      ownerResponse.andExpect(jsonPath("$.progress.answeredCount").value(2));
    } else {
      ownerResponse.andExpect(jsonPath("$.progress").doesNotExist());
    }
  }

  @Test void unauthenticatedNonparticipantAndLeftParticipantCannotReadState() throws Exception {
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    state(user(Role.PROFESSOR)).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", studentParticipantId);
    state(student).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_PARTICIPANT"));
    verifyNoInteractions(quizzes);
  }

  @Test void missingAndClosedRoomsReturnRoomSpecificErrors() throws Exception {
    mvc.perform(get("/api/rooms/9223372036854775807/state").header("Authorization", token(student)))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    room.close(Instant.now());
    rooms.saveAndFlush(room);
    state(student).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_CLOSED"));
    verifyNoInteractions(quizzes);
  }

  @Test void snapshotPortRunsInsideReadOnlyTransactionAndRoomLock() throws Exception {
    when(quizzes.read(anyLong(), anyLong())).thenAnswer(call -> {
      assertThat(locks.isLocked(room.getId())).isTrue();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
      assertThat(call.getArgument(1, Long.class)).isEqualTo(studentParticipantId);
      return RoomQuizStatePort.Snapshot.waiting();
    });
    state(student).andExpect(status().isOk());
    assertThat(locks.isLocked(room.getId())).isFalse();
  }

  @Test void snapshotWaitingForCloseLockReadsCommittedClosedState() throws Exception {
    try (var pool = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1);
      var future = locks.executeWithLock(room.getId(), () -> {
        var pending = pool.submit(() -> {
          started.countDown();
          return state(student).andReturn().getResponse().getStatus();
        });
        try {
          assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
          room.close(Instant.now());
          rooms.saveAndFlush(room);
          return pending;
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(error);
        }
      });
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(409);
    }
    verifyNoInteractions(quizzes);
  }

  private User user(Role role) {
    var user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only",
        role == Role.PROFESSOR ? "담당교수" : "학생실명", null, role));
    userIds.add(user.getId());
    return user;
  }

  private String path() { return "/api/rooms/" + room.getId() + "/state"; }

  private String token(User user) {
    return "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken();
  }

  private ResultActions state(User user) throws Exception {
    return mvc.perform(get(path()).header("Authorization", token(user)));
  }
}
