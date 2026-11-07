package com.sahmhoot.quiz.service.port;

import java.util.function.Supplier;

/**
 * 방 종료·퀴즈 처리·실시간 발행이 같은 방 잠금을 공유하기 위한 공개 계약.
 * 트랜잭션을 시작하기 전에 획득하며, 같은 스레드가 이미 잡은 잠금은 재진입할 수 있다.
 */
public interface RoomLockPort {
  <T> T executeWithLock(Long roomId, Supplier<T> task);

  void executeWithLock(Long roomId, Runnable task);
}
