package com.sahmhoot.quiz.service.port;

/**
 * 2번(Room - 승민 담당) 도메인과의 연동을 위한 인터페이스(포트).
 * 방 존재 확인, 소유자 검증, 방 상태 전환(OPEN <-> PLAYING), 참가자 식별을 담당한다.
 */
public interface RoomPort {

  /**
   * 방 정보를 조회한다. 존재하지 않으면 ROOM_NOT_FOUND 예외를 던진다.
   */
  RoomSnapshot getRoom(Long roomId);

  /**
   * 방 상태를 갱신한다 (예: OPEN -> PLAYING, PLAYING -> OPEN).
   */
  void updateStatus(Long roomId, RoomStatus status);

  /**
   * 주어진 방에서 해당 학생 사용자 ID(userId)의 참가자 ID(room_participants.id)를 조회한다.
   * 존재하지 않거나 퇴장한 경우 NOT_PARTICIPANT 예외를 던진다.
   */
  Long findParticipantId(Long roomId, Long userId);
}
