package com.sahmhoot.room;

/**
 * 예약 작업 관리자는 이 빈을 구현해 방별 예약을 취소한다.
 * 트랜잭션 밖, 방 잠금 안에서 동기 호출된다.
 * 실행 중 발생한 RuntimeException은 로그만 남기고 다음 취소 훅과 ROOM_ENDED 발행 시도는 계속한다.
 */
@FunctionalInterface
public interface RoomTaskCanceller {
  void cancelAll(Long roomId);
}
