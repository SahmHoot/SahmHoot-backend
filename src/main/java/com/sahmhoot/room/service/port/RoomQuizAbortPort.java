package com.sahmhoot.room.service.port;

/** 수업 종료용 퀴즈 중단. 호출자가 소유권을 확인하고 방 잠금과 트랜잭션을 유지한다. */
@FunctionalInterface
public interface RoomQuizAbortPort {
  /** 실행 중인 회차와 열린 문항만 중단한다. 방 상태는 변경하지 않고 중단 알림은 커밋 후 발행한다. */
  void abortActiveQuiz(Long roomId);
}
