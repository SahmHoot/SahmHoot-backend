package com.sahmhoot.questionset.repository;

import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.Visibility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QuestionSetQueryRepository {
  private final JdbcTemplate jdbc;
  private static final String COUNTS = """
      (SELECT COUNT(*) FROM questions q WHERE q.question_set_id = s.id) AS question_count,
      EXISTS(SELECT 1 FROM quiz_runs r WHERE r.question_set_id = s.id) AS used
      """;

  public List<QuestionSetSummaryResponse> mine(Long hostId) {
    return jdbc.query("SELECT s.*, " + COUNTS
        + " FROM question_sets s WHERE s.host_id = ? ORDER BY s.updated_at DESC, s.id DESC",
        (rs, row) -> summary(rs), hostId);
  }

  public QuestionSetSummaryResponse summary(Long setId) {
    return jdbc.queryForObject("SELECT s.*, " + COUNTS + " FROM question_sets s WHERE s.id = ?",
        (rs, row) -> summary(rs), setId);
  }

  public boolean used(Long setId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM quiz_runs WHERE question_set_id = ?)", Boolean.class, setId));
  }

  public String ownerName(Long hostId) {
    return jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, hostId);
  }

  private QuestionSetSummaryResponse summary(ResultSet rs) throws SQLException {
    return new QuestionSetSummaryResponse(rs.getLong("id"), rs.getString("title"),
        Visibility.valueOf(rs.getString("visibility")), rs.getLong("question_count"),
        rs.getBoolean("used"), rs.getTimestamp("updated_at").toInstant());
  }
}
