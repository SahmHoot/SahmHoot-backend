package com.sahmhoot.quiz.service.port;

/**
 * 2번(Room - 승민 담당) 도메인과의 연동을 위한 인터페이스(포트).
 * Room 담당이 구현하고 퀴즈가 사용하는 방 조회·상태 전환·참가자 식별 계약.
 * 호출자의 권한 판단과 방 잠금 획득은 퀴즈 서비스가 담당한다.
 */
public interface RoomPort {

  /**
   * 방 정보를 조회한다. 존재하지 않으면 ROOM_NOT_FOUND 예외를 던진다.
   */
  RoomSnapshot getRoom(Long roomId);

  /**
   * 방 상태를 갱신한다 (예: OPEN -> PLAYING, PLAYING -> OPEN).
   * 호출자의 트랜잭션에 참여하며, 존재하지 않으면 ROOM_NOT_FOUND 예외를 던진다.
   * 이 메서드에서 별도 트랜잭션을 열거나 이벤트를 발행하지 않는다.
   */
  void updateStatus(Long roomId, RoomStatus status);

  /**
   * 주어진 방에서 사용자 ID(users.id)에 대응하는 참가자 ID(room_participants.id)를 조회한다.
   * 존재하지 않거나 퇴장한 경우 NOT_PARTICIPANT 예외를 던진다.
   */
  Long findParticipantId(Long roomId, Long userId);
}
