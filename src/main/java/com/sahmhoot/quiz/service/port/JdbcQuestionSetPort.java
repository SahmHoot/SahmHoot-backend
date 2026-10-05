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

    String insertRunQuestionSql = "INSERT INTO run_questions (quiz_run_id, type, content, time_limit_seconds, order_no, status) VALUES (?, ?, ?, ?, ?, 'READY')";
    String choiceSql = "SELECT content, order_no, is_correct FROM choices WHERE question_id = ? ORDER BY order_no ASC";
    String insertRunChoiceSql = "INSERT INTO run_choices (run_question_id, content, order_no, is_correct) VALUES (?, ?, ?, ?)";

    for (Map<String, Object> q : questions) {
      Long originalQuestionId = ((Number) q.get("id")).longValue();
      String type = (String) q.get("type");
      String content = (String) q.get("content");
      int timeLimitSeconds = ((Number) q.get("time_limit_seconds")).intValue();
      int orderNo = ((Number) q.get("order_no")).intValue();

      KeyHolder keyHolder = new GeneratedKeyHolder();
      jdbcTemplate.update(connection -> {
        PreparedStatement ps = connection.prepareStatement(insertRunQuestionSql, Statement.RETURN_GENERATED_KEYS);
        ps.setLong(1, quizRunId);
        ps.setString(2, type);
        ps.setString(3, content);
        ps.setInt(4, timeLimitSeconds);
        ps.setInt(5, orderNo);
        return ps;
      }, keyHolder);

      Number generatedKey = keyHolder.getKey();
      if (generatedKey == null) {
        throw new IllegalStateException("run_questions insert did not return a generated id");
      }
      Long runQuestionId = generatedKey.longValue();

      List<Map<String, Object>> choices = jdbcTemplate.queryForList(choiceSql, originalQuestionId);
      for (Map<String, Object> c : choices) {
        String choiceContent = (String) c.get("content");
        int choiceOrderNo = ((Number) c.get("order_no")).intValue();
        Object isCorrectVal = c.get("is_correct");
        boolean isCorrect = (isCorrectVal instanceof Boolean b) ? b : (isCorrectVal instanceof Number n && n.intValue() == 1);
        jdbcTemplate.update(insertRunChoiceSql, runQuestionId, choiceContent, choiceOrderNo, isCorrect);
      }
    }

    log.info("Copied {} questions with choices for quizRunId={} from questionSetId={}", questions.size(), quizRunId, questionSetId);
    return questions.size();
  }
}
