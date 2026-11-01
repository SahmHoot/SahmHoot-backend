package com.sahmhoot.quiz.service.port;

import java.util.Set;

/**
 * Room의 접속 인원 집계(#45)가 구현하고 퀴즈의 전원 응답 판정(#28)이 사용하는 계약.
 */
public interface ParticipantPresencePort {

  /**
   * 접속 중인 비소유 참여자의 room_participants.id를 중복 없는 불변 집합으로 반환한다.
   * users.id와 WebSocket 세션 ID는 반환하지 않는다. 미등록·퇴장한 참여자와 소유자는 제외한다.
   * 접속자가 없으면 빈 집합을 반환하며, null이나 미구현을 빈 집합으로 대체하지 않는다.
   * 방이 없으면 ROOM_NOT_FOUND, 종료된 방이면 ROOM_CLOSED로 처리한다.
   * 구현 연동 전에는 이 포트를 소비하는 조기 마감을 활성화하지 않는다.
   */
  Set<Long> getOnlineParticipantIds(Long roomId);
}
