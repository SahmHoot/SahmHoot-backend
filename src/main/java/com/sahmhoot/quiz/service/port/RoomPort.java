package com.sahmhoot.quiz.service.port;

import java.util.Optional;

/**
 * 2번(Room - 승민 담당) 도메인과의 연동을 위한 인터페이스(포트).
 * 퀴즈가 방 모듈에게 필요한 일을 적어 둔 목록이며, 실제 코드는 room 모듈의
 * {@code QuizRoomAdapter}에 있다. 퀴즈는 이 인터페이스만 알고 방 모듈 내부는 모른다.
 *
 * <p>방 모듈은 데이터만 돌려주고 퀴즈 오류를 던지지 않는다. 찾는 대상이 없으면
 * {@link Optional#empty()}를 돌려주고, 어떤 오류로 응답할지는 퀴즈가 자기
 * {@code ErrorCode}로 정한다. 모듈마다 오류 처리기가 따로 있어서, 방 모듈이 던진
 * 오류는 퀴즈 API에서 처리되지 않기 때문이다.
 *
 * <p>호출자의 권한 판단과 방 잠금 획득은 퀴즈 서비스가 담당한다.
 */
public interface RoomPort {

  /**
   * 방 정보를 조회한다. 방이 없으면 빈 값을 돌려준다.
   */
  Optional<RoomSnapshot> findRoom(Long roomId);

  /**
   * 방 상태를 바꾼다. 퀴즈 시작은 OPEN → PLAYING, 퀴즈 중단·결과 닫기는 PLAYING → OPEN이다.
   * CLOSED로는 바꿀 수 없다(수업 종료는 방 모듈이 직접 처리한다).
   *
   * <p>호출자의 트랜잭션에 참여하며, 트랜잭션 없이 부르면 IllegalTransactionStateException으로 거부한다.
   * 이 메서드에서 별도 트랜잭션을 열거나 이벤트를 발행하지 않는다.
   * 퀴즈가 같은 트랜잭션에서 {@link #findRoom}으로 방을 확인한 뒤 부르므로, 방이 없거나
   * 허용되지 않는 전환이면 사용자 오류가 아니라 코드 버그로 보고 IllegalStateException을 던진다.
   */
  void updateStatus(Long roomId, RoomStatus status);

  /**
   * 주어진 방에서 사용자 ID(users.id)에 대응하는 참가자 ID(room_participants.id)를 조회한다.
   * 참가한 적이 없거나 퇴장했으면 빈 값을 돌려준다.
   */
  Optional<Long> findParticipantId(Long roomId, Long userId);
}
