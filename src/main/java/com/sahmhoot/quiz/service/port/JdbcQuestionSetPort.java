package com.sahmhoot.quiz.service.port;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * QuestionSetPort의 기본 구현체.
 * 4번(QuestionSet) 도메인 엔티티 머지 전까지 V1 Flyway 스키마에 정의된 question_sets 테이블을 직접 조회한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcQuestionSetPort implements QuestionSetPort {

  private static final String INSERT_RUN_QUESTION_SQL =
      "INSERT INTO run_questions (quiz_run_id, type, content, time_limit_seconds, order_no, status) "
          + "VALUES (?, ?, ?, ?, ?, 'READY')";
  private static final String SELECT_CHOICES_SQL =
      "SELECT content, order_no, is_correct FROM choices WHERE question_id = ? ORDER BY order_no ASC";
  private static final String INSERT_RUN_CHOICE_SQL =
      "INSERT INTO run_choices (run_question_id, content, order_no, is_correct) VALUES (?, ?, ?, ?)";

  private final JdbcTemplate jdbcTemplate;

  @Override
  public QuestionSetSnapshot getQuestionSet(Long questionSetId) {
    String sql = "SELECT id, host_id FROM question_sets WHERE id = ?";
    try {
      return jdbcTemplate.queryForObject(
          sql,
          (rs, rowNum) -> new QuestionSetSnapshot(
              rs.getLong("id"),
              rs.getLong("host_id")
          ),
          questionSetId
      );
    } catch (EmptyResultDataAccessException e) {
      log.warn("QuestionSet not found for questionSetId={}", questionSetId);
      throw new BusinessException(ErrorCode.QUESTION_SET_NOT_FOUND);
    }
  }

  @Override
  public int copyQuestionsToQuizRun(Long questionSetId, Long quizRunId) {
    String questionSql = "SELECT id, type, content, time_limit_seconds, order_no FROM questions WHERE question_set_id = ? ORDER BY order_no ASC";
    List<Map<String, Object>> questions = jdbcTemplate.queryForList(questionSql, questionSetId);
    if (questions.isEmpty()) {
      return 0;
    }

    for (Map<String, Object> questionRow : questions) {
      Long runQuestionId = insertRunQuestion(quizRunId, questionRow);
      Long originalQuestionId = ((Number) questionRow.get("id")).longValue();
      copyChoices(originalQuestionId, runQuestionId);
    }

    log.info("Copied {} questions with choices for quizRunId={} from questionSetId={}", questions.size(), quizRunId, questionSetId);
    return questions.size();
  }

  private Long insertRunQuestion(Long quizRunId, Map<String, Object> questionRow) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      PreparedStatement statement = connection.prepareStatement(INSERT_RUN_QUESTION_SQL, Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, quizRunId);
      statement.setString(2, (String) questionRow.get("type"));
      statement.setString(3, (String) questionRow.get("content"));
      statement.setInt(4, ((Number) questionRow.get("time_limit_seconds")).intValue());
      statement.setInt(5, ((Number) questionRow.get("order_no")).intValue());
      return statement;
    }, keyHolder);

    Number generatedKey = keyHolder.getKey();
    if (generatedKey == null) {
      throw new IllegalStateException("run_questions insert did not return a generated id");
    }
    return generatedKey.longValue();
  }

  private void copyChoices(Long originalQuestionId, Long runQuestionId) {
    List<Map<String, Object>> choices = jdbcTemplate.queryForList(SELECT_CHOICES_SQL, originalQuestionId);
    for (Map<String, Object> choiceRow : choices) {
      Object isCorrectValue = choiceRow.get("is_correct");
      boolean isCorrect = isCorrectValue instanceof Boolean bool
          ? bool
          : isCorrectValue instanceof Number number && number.intValue() == 1;
      jdbcTemplate.update(
          INSERT_RUN_CHOICE_SQL,
          runQuestionId,
          (String) choiceRow.get("content"),
          ((Number) choiceRow.get("order_no")).intValue(),
          isCorrect
      );
    }
  }
}
