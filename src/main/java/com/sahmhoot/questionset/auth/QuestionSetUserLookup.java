package com.sahmhoot.questionset.auth;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QuestionSetUserLookup {
  private final JdbcTemplate jdbc;
  public Optional<QuestionSetCurrentUser> findById(Long id) {
    return jdbc.query("SELECT id, role, name FROM users WHERE id = ?",
        (rs, row) -> new QuestionSetCurrentUser(rs.getLong("id"),
            rs.getString("role"), rs.getString("name")), id).stream().findFirst();
  }
}
