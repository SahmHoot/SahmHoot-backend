package com.sahmhoot.room.service;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RoomUserLookup {
  private final JdbcTemplate jdbc;

  public Optional<RoomUser> findById(Long userId) {
    return jdbc.query("SELECT id, role, name FROM users WHERE id = ?",
        (rs, row) -> new RoomUser(rs.getLong("id"), rs.getString("role"),
            rs.getString("name")), userId).stream().findFirst();
  }

  public record RoomUser(Long id, String role, String name) {}
}
