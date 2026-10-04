package com.sahmhoot.quiz.service.port;

/**
 * 수업 방 상태 (rooms.status 매핑).
 * 06 실시간 규격 및 ERD 기준: OPEN | PLAYING | CLOSED
 */
public enum RoomStatus {
  OPEN,
  PLAYING,
  CLOSED
}
