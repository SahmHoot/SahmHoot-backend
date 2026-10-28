package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.repository.*;
import com.sahmhoot.questionset.service.QuestionSetService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Committed fixtures verify that a failed set deletion restores both parent and children. */
@SpringBootTest
@ActiveProfiles("test")
class QuestionSetDeletionRollbackIntegrationTest {
  @Autowired QuestionSetService sets;
  @Autowired QuestionSetRepository setRepository;
  @Autowired QuestionRepository questionRepository;
  @Autowired ChoiceRepository choiceRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  private QuestionSetCurrentUser owner;
  private Long setId;
  private Long questionId;

  @BeforeEach void setup() {
    new TransactionTemplate(transactions).executeWithoutResult(status -> {
      String email = UUID.randomUUID() + "@test.invalid";
      jdbc.update("""
          INSERT INTO users(email,password_hash,name,role,email_verified,created_at,updated_at)
          VALUES (?, 'test-only', '롤백 테스트', 'PROFESSOR', true, UTC_TIMESTAMP(), UTC_TIMESTAMP())
          """, email);
      Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
      owner = new QuestionSetCurrentUser(id, "PROFESSOR", "롤백 테스트");
      setId = sets.create(owner, new CreateQuestionSetRequest("롤백", null, null)).id();
      var q = questionRepository.saveAndFlush(new Question(setRepository.findById(setId).orElseThrow(),
          QuestionType.TRUE_FALSE, "문항", 60, 1));
      questionId = q.getId();
      choiceRepository.saveAllAndFlush(List.of(new Choice(q, "O", 1, true), new Choice(q, "X", 2, false)));
    });
  }

  @AfterEach void cleanup() {
    if (setId != null && owner != null) sets.delete(setId, owner.id());
    if (owner != null) jdbc.update("DELETE FROM users WHERE id = ?", owner.id());
  }

  @Test void failureAfterDeletionRollsBackEveryChildAndParent() {
    assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
      sets.delete(setId, owner.id());
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM question_sets WHERE id = ?", Long.class, setId)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE id = ?", Long.class, questionId)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM choices WHERE question_id = ?", Long.class, questionId)).isZero();
      throw new IllegalStateException("test rollback");
    })).isInstanceOf(IllegalStateException.class).hasMessage("test rollback");
    assertThat(setRepository.existsById(setId)).isTrue();
    var detail = sets.detail(setId, owner.id());
    assertThat(detail.questions()).extracting(QuestionResponse::id).containsExactly(questionId);
    assertThat(detail.questions().getFirst().choices()).extracting(ChoiceResponse::content).containsExactly("O", "X");
  }
}
