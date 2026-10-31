package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "app.room.join-base-url=https://class.example/")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoomCreationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JwtProvider jwt;
  @Autowired UserRepository users;
  @Autowired RoomRepository rooms;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean JoinCodeGenerator codes;
  @MockitoSpyBean RoomParticipantRepository participants;
  private final JoinCodeGenerator realCodes = new JoinCodeGenerator();
  private User professor;

  @BeforeEach void setup() {
    professor = users.saveAndFlush(User.create(UUID.randomUUID() + "@test.invalid", "test-only",
        "김교수", null, Role.PROFESSOR));
    when(codes.generate()).thenAnswer(invocation -> realCodes.generate());
  }

  @AfterEach void cleanup() {
    jdbc.update("DELETE FROM room_participants WHERE room_id IN (SELECT id FROM rooms WHERE host_id = ?)", professor.getId());
    jdbc.update("DELETE FROM rooms WHERE host_id = ?", professor.getId());
    users.deleteById(professor.getId());
  }

  private ResultActions create(String body, Role tokenRole) throws Exception {
    String token = jwt.issue(professor.getId(), tokenRole).accessToken();
    return mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private long roomCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM rooms WHERE host_id = ?", Long.class, professor.getId());
  }

  @Test void professorCreatesRoomAndParticipatesWithTheirName() throws Exception {
    String body = create("{\"title\":\"자료구조 3주차\"}", Role.PROFESSOR)
        .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(6))
        .andExpect(jsonPath("$.title").value("자료구조 3주차"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    long id = ((Number) JsonPath.read(body, "$.id")).longValue();
    String code = JsonPath.read(body, "$.joinCode");
    assertThat(code).matches("[0-9]{6}");
    assertThat((String) JsonPath.read(body, "$.joinUrl")).isEqualTo("https://class.example/join/" + code);
    assertThat(Instant.parse(JsonPath.read(body, "$.createdAt"))).isBeforeOrEqualTo(Instant.now());
    Room saved = rooms.findById(id).orElseThrow();
    assertThat(saved.getHostId()).isEqualTo(professor.getId());
    assertThat(saved.getCode()).isEqualTo(code);
    assertThat(saved.getClosedAt()).isNull();
    assertThat(jdbc.queryForObject("SELECT nickname FROM room_participants WHERE room_id = ? AND user_id = ? AND left_at IS NULL",
        String.class, id, professor.getId())).isEqualTo("김교수");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_participants WHERE room_id = ?",
        Long.class, id)).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(ints = {1, 50})
  void acceptsTitleLengthBoundaries(int length) throws Exception {
    create("{\"title\":\"" + "가".repeat(length) + "\"}", Role.PROFESSOR)
        .andExpect(status().isCreated());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"title\":null}", "{\"title\":\"\"}", "{\"title\":\"   \"}"})
  void invalidTitleDoesNotCreateRoom(String body) throws Exception {
    create(body, Role.PROFESSOR).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    assertThat(roomCount()).isZero();
    verifyNoInteractions(codes);
  }

  @Test void titleLongerThan50IsRejected() throws Exception {
    create("{\"title\":\"" + "가".repeat(51) + "\"}", Role.PROFESSOR)
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    assertThat(roomCount()).isZero();
  }

  @Test void studentCannotCreateEvenWithOutdatedProfessorClaim() throws Exception {
    jdbc.update("UPDATE users SET role = 'STUDENT' WHERE id = ?", professor.getId());
    create("{\"title\":\"수업\"}", Role.PROFESSOR).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(roomCount()).isZero();
    verifyNoInteractions(codes);
  }

  @Test void tokenAndExistingUserAreRequired() throws Exception {
    mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수업\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    users.deleteById(professor.getId());
    create("{\"title\":\"수업\"}", Role.PROFESSOR).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest @ValueSource(strings = {"", "{", "null"})
  void malformedBodyUsesCommonValidationFormat(String body) throws Exception {
    create(body, Role.PROFESSOR).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("body"));
  }

  private String closedRoom() {
    String code;
    do { code = realCodes.generate(); } while (rooms.existsByCode(code));
    jdbc.update("INSERT INTO rooms(host_id,title,code,status,created_at,closed_at) VALUES (?, '종료된 방', ?, 'CLOSED', UTC_TIMESTAMP(), UTC_TIMESTAMP())",
        professor.getId(), code);
    return code;
  }

  @Test void databaseCodeCollisionWithClosedRoomRetriesInFreshTransaction() throws Exception {
    String taken = closedRoom();
    String available;
    do { available = realCodes.generate(); } while (rooms.existsByCode(available));
    when(codes.generate()).thenReturn(taken, available);
    create("{\"title\":\"새 수업\"}", Role.PROFESSOR).andExpect(status().isCreated())
        .andExpect(jsonPath("$.joinCode").value(available));
    verify(codes, times(2)).generate();
    assertThat(roomCount()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM room_participants WHERE user_id = ?",
        Long.class, professor.getId())).isEqualTo(1);
  }

  @Test void participantFailureRollsBackRoomInsteadOfLeavingOrphan() throws Exception {
    doThrow(new DataIntegrityViolationException("participant failure")).when(participants).saveAndFlush(any());
    create("{\"title\":\"수업\"}", Role.PROFESSOR).andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    assertThat(roomCount()).isZero();
    verify(codes).generate();
  }

  @Test void exhaustedCollisionsStopWithoutCreatingRoom() throws Exception {
    when(codes.generate()).thenReturn(closedRoom());
    create("{\"title\":\"수업\"}", Role.PROFESSOR).andExpect(status().isInternalServerError());
    verify(codes, times(10)).generate();
    assertThat(roomCount()).isEqualTo(1);
  }

  @Test void generatedCodesHaveSixAsciiDigits() {
    for (int i = 0; i < 100; i++) assertThat(realCodes.generate()).matches("[0-9]{6}");
  }
}
