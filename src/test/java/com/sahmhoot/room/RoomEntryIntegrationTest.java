package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.JoinCodeGenerator;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomEntryIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired RoomParticipantRepository participants;
  @Autowired RoomLockManager locks;
  @Autowired JdbcTemplate jdbc;
  private final JoinCodeGenerator codes = new JoinCodeGenerator();
  private final List<Long> userIds = new ArrayList<>();
  private User owner;
  private User student;
  private Room room;

  @BeforeEach void setup() {
    owner = user(Role.PROFESSOR);
    student = user(Role.STUDENT);
    room = room();
  }

  @AfterEach void cleanup() {
    if (owner != null) {
      jdbc.update("DELETE FROM room_participants WHERE room_id IN (SELECT id FROM rooms WHERE host_id = ?)", owner.getId());
      jdbc.update("DELETE FROM rooms WHERE host_id = ?", owner.getId());
    }
    userIds.forEach(users::deleteById);
  }

  @Test void lookupMatchesContractForNewParticipantAndOwner() throws Exception {
    perform(get(path()), student).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(jsonPath("$.roomId").value(room.getId()))
        .andExpect(jsonPath("$.title").value("수업"))
        .andExpect(jsonPath("$.ownerName").value(owner.getName()))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.joined").value(false))
        .andExpect(jsonPath("$.isOwner").value(false))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"myNickname\":null")));
    perform(get(path()), owner).andExpect(status().isOk())
        .andExpect(jsonPath("$.joined").value(true))
        .andExpect(jsonPath("$.isOwner").value(true))
        .andExpect(jsonPath("$.myNickname").value(owner.getName()));
  }

  @Test void firstJoinAndRejoinKeepSameRowNicknameAndJoinedAt() throws Exception {
    join(student, "{\"nickname\":\"  학생닉네임  \"}")
        .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$.roomId").value(room.getId()))
        .andExpect(jsonPath("$.nickname").value("학생닉네임"));
    var original = participants.findByRoomIdAndUserId(room.getId(), student.getId()).orElseThrow();
    LocalDateTime joinedAt = jdbc.queryForObject(
        "SELECT joined_at FROM room_participants WHERE id = ?", LocalDateTime.class, original.getId());
    join(student, "{\"nickname\":\"다른닉네임\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.participantId").value(original.getId()))
        .andExpect(jsonPath("$.nickname").value("학생닉네임"));
    assertThat(participantCount(student)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT joined_at FROM room_participants WHERE id = ?",
        LocalDateTime.class, original.getId())).isEqualTo(joinedAt);
    perform(get(path()), student).andExpect(status().isOk())
        .andExpect(jsonPath("$.joined").value(true))
        .andExpect(jsonPath("$.myNickname").value("학생닉네임"));
  }

  @Test void previouslyLeftParticipantIsReactivatedInSameRow() throws Exception {
    var original = participants.saveAndFlush(new RoomParticipant(room.getId(), student.getId(), "기존학생"));
    jdbc.update("UPDATE room_participants SET left_at = UTC_TIMESTAMP() WHERE id = ?", original.getId());
    perform(get(path()), student).andExpect(status().isOk())
        .andExpect(jsonPath("$.joined").value(false))
        .andExpect(jsonPath("$.myNickname").value("기존학생"));
    join(student, "{\"nickname\":\"기존학생\"}").andExpect(status().isOk())
        .andExpect(jsonPath("$.participantId").value(original.getId()))
        .andExpect(jsonPath("$.nickname").value("기존학생"));
    assertThat(participants.findByRoomIdAndUserIdAndLeftAtIsNull(room.getId(), student.getId())).isPresent();
    assertThat(participantCount(student)).isEqualTo(1);
    perform(get(path()), student).andExpect(status().isOk())
        .andExpect(jsonPath("$.joined").value(true))
        .andExpect(jsonPath("$.myNickname").value("기존학생"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"교\\u200B수", "\\u1100\\u116D\\u1109\\u116E",
      "\\u00A0\\u00A0", "\\u200B\\u200B", "\\u3164\\u3164",
      "\\u115F\\u115F", "\\u1160\\u1160", "\\uFFA0\\uFFA0",
      "\\u2007\\u2007", "\\u202F\\u202F", "가\\u0001나", "가\\u202E나"})
  void invisibleOrDisguisedNicknamesAreRejected(String encodedNickname) throws Exception {
    join(student, "{\"nickname\":\"" + encodedNickname + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NICKNAME_NOT_ALLOWED"));
    assertThat(participantCount(student)).isZero();
  }

  @Test void ordinarySpaceInsideNicknameIsPreserved() throws Exception {
    join(student, "{\"nickname\":\"  조용한 한라봉  \"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.nickname").value("조용한 한라봉"));
    assertThat(participants.findByRoomIdAndUserId(room.getId(), student.getId())
        .orElseThrow().getNickname()).isEqualTo("조용한 한라봉");
  }

  @Test void decomposedHangulIsNormalizedBeforeLengthValidationAndStorage() throws Exception {
    String nickname = "가".repeat(10);
    String decomposed = Normalizer.normalize(nickname, Normalizer.Form.NFD);
    join(student, "{\"nickname\":\"  " + decomposed + "  \"}")
        .andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value(nickname));
    assertThat(participants.findByRoomIdAndUserId(room.getId(), student.getId())
        .orElseThrow().getNickname()).isEqualTo(nickname);
  }

  @ParameterizedTest @ValueSource(ints = {2, 10})
  void acceptsNormalizedNicknameLengthBoundaries(int length) throws Exception {
    join(student, "{\"nickname\":\"  " + "가".repeat(length) + "  \"}")
        .andExpect(status().isCreated()).andExpect(jsonPath("$.nickname").value("가".repeat(length)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"nickname\":null}", "{\"nickname\":\"\"}",
      "{\"nickname\":\"   \"}", "{\"nickname\":\" 가 \"}", "{\"nickname\":\"가나다라마바사아자차카\"}"})
  void invalidNicknameHasValidationFieldAndDoesNotPersist(String body) throws Exception {
    join(student, body).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"));
    assertThat(participantCount(student)).isZero();
  }

  @Test void professorWordIsForbiddenAndDuplicateNicknameIsScopedToRoom() throws Exception {
    join(student, "{\"nickname\":\"교수님\"}").andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NICKNAME_NOT_ALLOWED"));
    join(student, "{\"nickname\":\"학생닉네임\"}").andExpect(status().isCreated());
    User other = user(Role.STUDENT);
    join(other, "{\"nickname\":\"학생닉네임\"}").andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NICKNAME_ALREADY_USED"));
    Room another = room();
    perform(post("/api/rooms/by-code/" + another.getCode() + "/participants")
        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"학생닉네임\"}"), other)
        .andExpect(status().isCreated());
  }

  @Test void playingRoomAllowsEntryRegardlessOfGlobalRole() throws Exception {
    room.startPlaying();
    rooms.saveAndFlush(room);
    User professor = user(Role.PROFESSOR);
    join(professor, "{\"nickname\":\"참여자\"}").andExpect(status().isCreated());
    perform(get(path()), professor).andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PLAYING"))
        .andExpect(jsonPath("$.isOwner").value(false));
  }

  @Test void closedRoomRejectsLookupNewEntryAndReentry() throws Exception {
    participants.saveAndFlush(new RoomParticipant(room.getId(), student.getId(), "기존학생"));
    room.close(Instant.now());
    rooms.saveAndFlush(room);
    perform(get(path()), student).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
    join(student, "{\"nickname\":\"기존학생\"}").andExpect(status().isNotFound());
    User newcomer = user(Role.STUDENT);
    join(newcomer, "{\"nickname\":\"새학생\"}").andExpect(status().isNotFound());
    assertThat(participantCount(newcomer)).isZero();
  }

  @Test void sharedRequestLimitCountsMissingCodesAndRespectsUserIsolation() throws Exception {
    for (int i = 0; i < 10; i++) {
      perform(get("/api/rooms/by-code/missing"), student).andExpect(status().isNotFound());
      join(student, "{\"nickname\":\"교수님\"}").andExpect(status().isBadRequest());
    }
    perform(get(path()), student).andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    join(student, "{\"nickname\":\"학생\"}").andExpect(status().isTooManyRequests());
    perform(get(path()), owner).andExpect(status().isOk());
  }

  @Test void unauthenticatedRequestsCannotLookupOrJoin() throws Exception {
    mvc.perform(get(path())).andExpect(status().isUnauthorized());
    mvc.perform(post(path() + "/participants").contentType(MediaType.APPLICATION_JSON)
        .content("{\"nickname\":\"학생\"}")).andExpect(status().isUnauthorized());
  }

  @Test void concurrentReentryCreatesOnlyOneParticipant() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = pool.submit(() -> { start.await(); return joinStatus(student, "학생"); });
      var second = pool.submit(() -> { start.await(); return joinStatus(student, "학생"); });
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 200);
    }
    assertThat(participantCount(student)).isEqualTo(1);
  }

  @Test void concurrentSameNicknameAllowsOneStudent() throws Exception {
    User other = user(Role.STUDENT);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first = pool.submit(() -> { start.await(); return joinStatus(student, "같은닉네임"); });
      var second = pool.submit(() -> { start.await(); return joinStatus(other, "같은닉네임"); });
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
    }
    assertThat(participantCount(student) + participantCount(other)).isEqualTo(1);
  }

  @Test void concurrentClosePreventsLateEntry() throws Exception {
    try (var pool = Executors.newSingleThreadExecutor()) {
      var started = new CountDownLatch(1);
      var future = locks.executeWithLock(room.getId(), () -> {
        var pending = pool.submit(() -> { started.countDown(); return joinStatus(student, "학생"); });
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
      assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(404);
    }
    assertThat(participantCount(student)).isZero();
  }

  private User user(Role role) {
    User user = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only",
        role == Role.PROFESSOR ? "담당교수" : "학생", null, role));
    userIds.add(user.getId());
    return user;
  }

  private Room room() {
    String code;
    do { code = codes.generate(); } while (rooms.existsByCode(code));
    Room created = rooms.saveAndFlush(new Room(owner.getId(), "수업", code));
    participants.saveAndFlush(new RoomParticipant(created.getId(), owner.getId(), owner.getName()));
    return created;
  }

  private String path() { return "/api/rooms/by-code/" + room.getCode(); }

  private ResultActions perform(MockHttpServletRequestBuilder request, User user) throws Exception {
    return mvc.perform(request.header("Authorization", "Bearer " + jwt.issue(user.getId(), user.getRole()).accessToken()));
  }

  private ResultActions join(User user, String body) throws Exception {
    return perform(post(path() + "/participants").contentType(MediaType.APPLICATION_JSON).content(body), user);
  }

  private int joinStatus(User user, String nickname) throws Exception {
    return join(user, "{\"nickname\":\"" + nickname + "\"}").andReturn().getResponse().getStatus();
  }

  private long participantCount(User user) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM room_participants WHERE room_id = ? AND user_id = ?",
        Long.class, room.getId(), user.getId());
  }
}
