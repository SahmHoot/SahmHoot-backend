package com.sahmhoot.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 구독·전송 권한 확인용 조회. 방 엔티티는 방 담당 영역이라 여기서는 SQL로만 읽는다.
 *
 * <p>방 안 권한은 rooms.host_id == 현재 사용자로만 판단(06 3절).
 */
@Component
@RequiredArgsConstructor
public class RoomAccessChecker {

  private final JdbcTemplate jdbcTemplate;

  public boolean isParticipant(long roomId, long userId) {
    Integer n =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM room_participants WHERE room_id = ? AND user_id = ?",
            Integer.class,
            roomId,
            userId);
    return n != null && n > 0;
  }

  public boolean isOwner(long roomId, long userId) {
    Integer n =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM rooms WHERE id = ? AND host_id = ?", Integer.class, roomId, userId);
    return n != null && n > 0;
  }
}
