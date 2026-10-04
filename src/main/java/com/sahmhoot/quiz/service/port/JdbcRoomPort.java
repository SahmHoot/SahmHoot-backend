package com.sahmhoot.quiz.service.port;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RoomPort의 기본 구현체.
 * 2번(Room) 도메인 엔티티 머지 전까지 V1 Flyway 스키마에 정의된 rooms, room_participants 테이블을
 * 직접 조회/갱신하며, 스프링 트랜잭션과 동일 커넥션을 공유한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcRoomPort implements RoomPort {

  private final JdbcTemplate jdbcTemplate;

  @Override
  public RoomSnapshot getRoom(Long roomId) {
    String sql = "SELECT id, host_id, status FROM rooms WHERE id = ?";
    try {
      return jdbcTemplate.queryForObject(
          sql,
          (rs, rowNum) -> new RoomSnapshot(
              rs.getLong("id"),
              rs.getLong("host_id"),
              RoomStatus.valueOf(rs.getString("status"))
          ),
          roomId
      );
    } catch (EmptyResultDataAccessException e) {
      log.warn("Room not found for roomId={}", roomId);
      throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
    }
  }

  @Override
  public void updateStatus(Long roomId, RoomStatus status) {
    String sql = "UPDATE rooms SET status = ? WHERE id = ?";
    int updated = jdbcTemplate.update(sql, status.name(), roomId);
    if (updated == 0) {
      log.warn("Failed to update status for non-existing roomId={}", roomId);
      throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
    }
    log.info("Updated room status: roomId={}, status={}", roomId, status);
  }

  @Override
  public Long findParticipantId(Long roomId, Long userId) {
    String sql = "SELECT id FROM room_participants WHERE room_id = ? AND user_id = ? AND left_at IS NULL";
    try {
      return jdbcTemplate.queryForObject(sql, Long.class, roomId, userId);
    } catch (EmptyResultDataAccessException e) {
      log.warn("User {} is not an active participant in roomId={}", userId, roomId);
      throw new BusinessException(ErrorCode.NOT_PARTICIPANT);
    }
  }
}
