package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.repository.*;
import com.sahmhoot.questionset.service.QuestionSetService;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class QuestionSetPersistenceIntegrationTest {
  @Autowired QuestionSetService sets;
  @Autowired QuestionSetRepository setRepository;
  @Autowired QuestionRepository questionRepository;
  @Autowired ChoiceRepository choiceRepository;
  @Autowired QuestionSetQueryRepository query;
  @Autowired QuestionSetPort quizPort;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  private QuestionSetCurrentUser professor;
  private QuestionSetCurrentUser student;

  @BeforeEach void setup() {
    professor = createUser("PROFESSOR");
    student = createUser("STUDENT");
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  private QuestionSetCurrentUser createUser(String role) {
    String email = UUID.randomUUID() + "@test.invalid";
    jdbc.update("""
        INSERT INTO users(email,password_hash,name,role,email_verified,created_at,updated_at)
        VALUES (?, 'test-only', '테스트', ?, true, UTC_TIMESTAMP(), UTC_TIMESTAMP())
        """, email, role);
    Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    return new QuestionSetCurrentUser(id, role, "테스트");
  }
  private Long create(Visibility visibility) {
    return sets.create(professor, new CreateQuestionSetRequest("세트", "설명", visibility)).id();
  }
  private Question question(Long setId, int order) {
    // Seed existing V1 rows directly; issue #49 does not expose question write APIs.
    return questionRepository.saveAndFlush(new Question(setRepository.findById(setId).orElseThrow(),
        QuestionType.TRUE_FALSE, "문항 " + order, 15, order));
  }
  private void choices(Question question) {
    choiceRepository.saveAllAndFlush(List.of(new Choice(question, "X", 2, false),
        new Choice(question, "O", 1, true)));
  }
  private Long quizRun(Long setId, QuizRunStatus status) {
    String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    jdbc.update("""
        INSERT INTO rooms(host_id,title,code,status,created_at)
        VALUES (?, '테스트 방', ?, 'OPEN', UTC_TIMESTAMP())
        """, professor.id(), code);
    Long roomId = jdbc.queryForObject("SELECT id FROM rooms WHERE code = ?", Long.class, code);
    jdbc.update("INSERT INTO quiz_runs(room_id,question_set_id,status) VALUES (?, ?, ?)",
        roomId, setId, status.name());
    return jdbc.queryForObject("SELECT id FROM quiz_runs WHERE room_id = ?", Long.class, roomId);
  }

  private static final String QUESTION_BODY = """
      {"type":"TRUE_FALSE","content":"새 문항",
       "choices":[{"content":"O","isCorrect":true},{"content":"X","isCorrect":false}]}
      """;

  @Test void httpQuestionCrudPersistsChoicesAndCompactsOrder() throws Exception {
    Long setId = create(null);
    String path = "/api/question-sets/" + setId + "/questions";
    var authenticated = user(professor.id().toString());
    for (int i = 1; i <= 3; i++) {
      mvc.perform(post(path).with(authenticated).contentType(MediaType.APPLICATION_JSON).content(QUESTION_BODY))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.orderNo").value(i))
          .andExpect(jsonPath("$.timeLimitSeconds").value(60))
          .andExpect(jsonPath("$.choices[1].orderNo").value(2));
    }
    var initial = sets.detail(setId, professor.id()).questions();
    Long middleId = initial.get(1).id();
    var oldChoices = initial.get(1).choices().stream().map(ChoiceResponse::id).toList();
    mvc.perform(put(path + "/" + middleId).with(authenticated).contentType(MediaType.APPLICATION_JSON)
        .content("""
            {"type":"MULTIPLE_CHOICE","content":"수정 문항","timeLimitSeconds":10,
             "choices":[{"content":"A","isCorrect":false},{"content":"B","isCorrect":true},
                        {"content":"C","isCorrect":false},{"content":"D","isCorrect":false}]}
            """))
        .andExpect(status().isOk()).andExpect(jsonPath("$.orderNo").value(2))
        .andExpect(jsonPath("$.choices.length()").value(4));
    assertThat(choiceRepository.findAllById(oldChoices)).isEmpty();
    var updated = sets.detail(setId, professor.id()).questions().get(1);
    assertThat(updated.content()).isEqualTo("수정 문항");
    assertThat(updated.timeLimitSeconds()).isEqualTo(10);
    assertThat(updated.choices()).extracting(ChoiceResponse::isCorrect).containsExactly(false, true, false, false);
    mvc.perform(delete(path + "/" + middleId).with(authenticated)).andExpect(status().isNoContent());
    assertThat(questionRepository.findById(middleId)).isEmpty();
    assertThat(choiceRepository.findByQuestionIdInOrderByOrderNoAsc(List.of(middleId))).isEmpty();
    assertThat(sets.detail(setId, professor.id()).questions()).extracting(QuestionResponse::orderNo)
        .containsExactly(1, 2);
    assertThat(sets.detail(setId, professor.id()).questions()).extracting(QuestionResponse::id)
        .containsExactly(initial.getFirst().id(), initial.getLast().id());
    mvc.perform(post(path).with(authenticated).contentType(MediaType.APPLICATION_JSON).content(QUESTION_BODY))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.orderNo").value(3));
    mvc.perform(delete(path + "/" + initial.getFirst().id()).with(authenticated))
        .andExpect(status().isNoContent());
    assertThat(sets.detail(setId, professor.id()).questions()).extracting(QuestionResponse::orderNo)
        .containsExactly(1, 2);
  }

  @Test void publicSetStillRequiresOwnershipForEveryQuestionMutation() throws Exception {
    Long setId = create(Visibility.PUBLIC);
    var q = question(setId, 1);
    choices(q);
    String path = "/api/question-sets/" + setId + "/questions";
    for (var actor : List.of(student, createUser("PROFESSOR"))) {
      for (var request : List.of(post(path), put(path + "/" + q.getId()), delete(path + "/" + q.getId()))) {
        mvc.perform(request.with(user(actor.id().toString())).contentType(MediaType.APPLICATION_JSON)
            .content(QUESTION_BODY)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
      }
    }
    assertThat(sets.detail(setId, professor.id()).questions()).hasSize(1);
    assertThat(choiceRepository.findByQuestionQuestionSetId(setId)).hasSize(2);
  }

  @Test void questionFromAnotherSetCannotBeUpdatedOrDeleted() throws Exception {
    Long first = create(null);
    Long second = create(null);
    var foreign = question(second, 1);
    choices(foreign);
    String path = "/api/question-sets/" + first + "/questions/" + foreign.getId();
    for (var request : List.of(put(path), delete(path))) {
      mvc.perform(request.with(user(professor.id().toString())).contentType(MediaType.APPLICATION_JSON)
          .content(QUESTION_BODY)).andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
    }
    assertThat(sets.detail(second, professor.id()).questions()).hasSize(1);
  }

  @Test void missingSetReturnsQuestionNotFoundForUpdateAndDelete() throws Exception {
    Long missingSetId = Long.MAX_VALUE;
    assertThat(setRepository.existsById(missingSetId)).isFalse();
    String path = "/api/question-sets/" + missingSetId + "/questions/1";
    for (var request : List.of(put(path), delete(path))) {
      mvc.perform(request.with(user(professor.id().toString())).contentType(MediaType.APPLICATION_JSON)
          .content(QUESTION_BODY)).andExpect(status().isNotFound())
          .andExpect(jsonPath("$.status").value(404))
          .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
    }
  }

  @Test void missingQuestionReturnsNotFoundForOwnerAndForbiddenForNonOwner() throws Exception {
    Long setId = create(Visibility.PUBLIC);
    Long missingQuestionId = Long.MAX_VALUE;
    assertThat(questionRepository.existsById(missingQuestionId)).isFalse();
    String path = "/api/question-sets/" + setId + "/questions/" + missingQuestionId;
    for (var actor : List.of(professor, student)) {
      boolean owner = actor.id().equals(professor.id());
      for (var request : List.of(put(path), delete(path))) {
        mvc.perform(request.with(user(actor.id().toString())).contentType(MediaType.APPLICATION_JSON)
            .content(QUESTION_BODY)).andExpect(status().is(owner ? 404 : 403))
            .andExpect(jsonPath("$.code").value(owner ? "QUESTION_NOT_FOUND" : "FORBIDDEN"));
      }
    }
  }

  @Test void missingSetStillUsesSetNotFoundForQuestionCreationAndSetCrud() throws Exception {
    Long missingSetId = Long.MAX_VALUE;
    assertThat(setRepository.existsById(missingSetId)).isFalse();
    String path = "/api/question-sets/" + missingSetId;
    for (var request : List.of(post(path + "/questions").content(QUESTION_BODY),
        get(path), patch(path).content("{}"), delete(path))) {
      mvc.perform(request.with(user(professor.id().toString())).contentType(MediaType.APPLICATION_JSON))
          .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUESTION_SET_NOT_FOUND"));
    }
  }

  @Test void invalidQuestionUpdatePreservesExistingData() throws Exception {
    Long setId = create(null);
    var q = question(setId, 1);
    choices(q);
    mvc.perform(put("/api/question-sets/" + setId + "/questions/" + q.getId())
        .with(user(professor.id().toString())).contentType(MediaType.APPLICATION_JSON)
        .content(QUESTION_BODY.replace("false", "true")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    assertThat(sets.detail(setId, professor.id()).questions().getFirst().content()).isEqualTo("문항 1");
    assertThat(choiceRepository.findByQuestionQuestionSetId(setId)).hasSize(2);
  }

  @Test void createDefaultsAndReadModels() {
    Long id = create(null);
    var summary = query.summary(id);
    assertThat(summary.visibility()).isEqualTo(Visibility.PRIVATE);
    assertThat(summary.used()).isFalse();
    assertThat(summary.questionCount()).isZero();
    assertThat(summary.updatedAt()).isNotNull();
    assertThat(sets.mine(professor)).extracting(QuestionSetSummaryResponse::id).containsExactly(id);
    var detail = sets.detail(id, professor.id());
    assertThat(detail.ownerName()).isEqualTo("테스트");
    assertThat(detail.description()).isEqualTo("설명");
    assertThat(detail.questions()).isEmpty();
  }

  @Test void mineIsScopedAndSortedWithQuestionCountAndUsage() {
    Long first = create(null);
    Long second = create(Visibility.PUBLIC);
    Long third = create(null);
    var other = createUser("PROFESSOR");
    sets.create(other, new CreateQuestionSetRequest("다른 교수", null, null));
    choices(question(first, 1));
    quizRun(first, QuizRunStatus.ABORTED);
    jdbc.update("UPDATE question_sets SET updated_at = '2026-01-01 00:00:00' WHERE host_id = ?", professor.id());
    jdbc.update("UPDATE question_sets SET updated_at = '2026-01-02 00:00:00' WHERE id = ?", first);
    var mine = sets.mine(professor);
    assertThat(mine).extracting(QuestionSetSummaryResponse::id).containsExactly(first, third, second);
    assertThat(mine.getFirst().questionCount()).isEqualTo(1);
    assertThat(mine.getFirst().used()).isTrue();
    assertThat(mine.get(1).used()).isFalse();
  }

  @Test void httpNullPoliciesAndWhitespaceArePersisted() throws Exception {
    var authenticated = user(professor.id().toString());
    mvc.perform(post("/api/question-sets").with(authenticated).contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\" 세트 \",\"description\":\" 설명 \",\"visibility\":null}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value(" 세트 "))
        .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    Long id = jdbc.queryForObject("SELECT id FROM question_sets WHERE host_id = ?", Long.class, professor.id());
    String path = "/api/question-sets/" + id;
    mvc.perform(patch(path).with(authenticated).contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\" 새 제목 \"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.title").value(" 새 제목 "));
    assertThat(sets.detail(id, professor.id()).description()).isEqualTo(" 설명 ");
    for (String field : List.of("title", "visibility")) {
      mvc.perform(patch(path).with(authenticated).contentType(MediaType.APPLICATION_JSON)
          .content("{\"" + field + "\":null}"))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }
    mvc.perform(patch(path).with(authenticated).contentType(MediaType.APPLICATION_JSON)
        .content("{\"description\":null}"))
        .andExpect(status().isOk());
    var detail = sets.detail(id, professor.id());
    assertThat(detail.description()).isNull();
    assertThat(detail.title()).isEqualTo(" 새 제목 ");
    assertThat(detail.visibility()).isEqualTo(Visibility.PRIVATE);
  }

  @Test void httpEnforcesProfessorCreationAndSetOwnership() throws Exception {
    mvc.perform(post("/api/question-sets").with(user(student.id().toString()))
        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"금지\"}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    Long id = create(null);
    var other = createUser("PROFESSOR");
    for (var actor : List.of(student, other)) {
      var authenticated = user(actor.id().toString());
      String path = "/api/question-sets/" + id;
      mvc.perform(get(path).with(authenticated)).andExpect(status().isForbidden());
      mvc.perform(patch(path).with(authenticated).contentType(MediaType.APPLICATION_JSON)
          .content("{\"title\":\"변경 금지\"}"))
          .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
      mvc.perform(delete(path).with(authenticated)).andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("FORBIDDEN"));
      mvc.perform(get("/api/question-sets/mine").with(authenticated))
          .andExpect(status().is(actor.professor() ? 200 : 403));
    }
    assertThat(sets.detail(id, professor.id()).title()).isEqualTo("세트");
  }

  @Test void detailReadsExistingQuestionsAndChoicesInOrder() {
    Long id = create(null);
    var second = question(id, 2);
    var first = question(id, 1);
    choices(second);
    choices(first);
    var detail = sets.detail(id, professor.id());
    assertThat(detail.questions()).extracting(QuestionResponse::id).containsExactly(first.getId(), second.getId());
    assertThat(detail.questions()).extracting(QuestionResponse::timeLimitSeconds).containsExactly(15, 15);
    for (var item : detail.questions()) {
      assertThat(item.choices()).extracting(ChoiceResponse::content).containsExactly("O", "X");
      assertThat(item.choices()).extracting(ChoiceResponse::isCorrect).containsExactly(true, false);
    }
  }

  @Test void sharedDetailRetainsV15VisibilityPolicy() {
    Long id = create(Visibility.PUBLIC);
    choices(question(id, 1));
    assertThat(sets.detail(id, student.id()).questions().getFirst().choices().getFirst().isCorrect()).isTrue();
  }

  @Test void deleteUnusedSetDeletesExistingChildren() throws Exception {
    Long id = create(null);
    var first = question(id, 1);
    var second = question(id, 2);
    choices(first);
    choices(second);
    mvc.perform(delete("/api/question-sets/" + id).with(user(professor.id().toString())))
        .andExpect(status().isNoContent()).andExpect(content().string(""));
    assertThat(setRepository.findById(id)).isEmpty();
    assertThat(questionRepository.findByQuestionSetIdOrderByOrderNoAsc(id)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM choices WHERE question_id IN (?, ?)",
        Long.class, first.getId(), second.getId())).isZero();
  }

  @ParameterizedTest @EnumSource(QuizRunStatus.class)
  void anyQuizHistoryPreventsSetDeletion(QuizRunStatus runStatus) throws Exception {
    Long id = create(null);
    choices(question(id, 1));
    quizRun(id, runStatus);
    mvc.perform(delete("/api/question-sets/" + id).with(user(professor.id().toString())))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUESTION_SET_IN_USE"));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_sets WHERE id = ?", Long.class, id)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE question_set_id = ?", Long.class, id)).isEqualTo(1);
    assertThat(choiceRepository.findByQuestionQuestionSetId(id)).hasSize(2);
  }

  @Test void metadataEditsPreserveQuizSnapshotsAndUsage() {
    Long id = create(null);
    choices(question(id, 1));
    Long runId = quizRun(id, QuizRunStatus.ABORTED);
    assertThat(quizPort.getQuestionSet(id).hostId()).isEqualTo(professor.id());
    assertThat(quizPort.copyQuestionsToQuizRun(id, runId)).isEqualTo(1);
    var patch = new UpdateQuestionSetRequest();
    patch.readTitle("변경");
    patch.readVisibility(Visibility.PUBLIC);
    var updated = sets.update(id, professor.id(), patch);
    assertThat(updated.used()).isTrue();
    assertThat(updated.visibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(jdbc.queryForObject("SELECT content FROM run_questions WHERE quiz_run_id = ?",
        String.class, runId)).isEqualTo("문항 1");
    assertThat(jdbc.queryForObject("SELECT time_limit_seconds FROM run_questions WHERE quiz_run_id = ?",
        Integer.class, runId)).isEqualTo(15);
    assertThat(jdbc.queryForObject("""
        SELECT c.content FROM run_choices c JOIN run_questions q ON q.id = c.run_question_id
        WHERE q.quiz_run_id = ? AND c.is_correct = true
        """, String.class, runId)).isEqualTo("O");
  }

  @Test void httpSetCrudFlowUsesRealServicesAndRepositories() throws Exception {
    var authenticated = user(professor.id().toString());
    mvc.perform(post("/api/question-sets").with(authenticated).contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"HTTP 세트\",\"description\":\"유지\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.used").value(false));
    Long id = jdbc.queryForObject("SELECT id FROM question_sets WHERE host_id = ?", Long.class, professor.id());
    String path = "/api/question-sets/" + id;
    mvc.perform(patch(path).with(authenticated).contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"새 제목\",\"visibility\":\"PUBLIC\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"));
    mvc.perform(get(path).with(authenticated)).andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value("유지")).andExpect(jsonPath("$.questions").isEmpty());
    mvc.perform(get("/api/question-sets/mine").with(authenticated)).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(id)).andExpect(jsonPath("$[0].questionCount").value(0));
    mvc.perform(delete(path).with(authenticated)).andExpect(status().isNoContent());
    mvc.perform(get(path).with(authenticated)).andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("QUESTION_SET_NOT_FOUND"));
  }

  @Test void webSecurityRejectsAnonymousBeforeInvalidBody() throws Exception {
    mvc.perform(post("/api/question-sets").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }
}
