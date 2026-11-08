package com.sahmhoot.quiz.lock;

import com.sahmhoot.quiz.service.port.RoomLockPort;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 방 단위 동시성 제어를 위한 잠금 관리자.
 * 06 실시간 메시지 규격 5절 기준:
 * - 방 번호(roomId)로 고른 64개 고정 스트라이핑 잠금 중 하나를 사용.
 * - 한 방의 퀴즈 상태를 바꾸는 동작(시작, 응답, 마감, 다음 문항, 중단, 결과 닫기, 종료)을 직렬화하여 처리.
 * - 새 작업은 트랜잭션보다 먼저 잠금을 획득한다. 이미 잡은 같은 잠금 안에서의 재진입은 허용한다.
 */
@Component
public class RoomLockManager implements RoomLockPort {

  private static final int STRIPE_COUNT = 64;
  private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

  public RoomLockManager() {
    for (int i = 0; i < STRIPE_COUNT; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  private ReentrantLock getLock(Long roomId) {
    if (roomId == null) {
      throw new IllegalArgumentException("roomId must not be null");
    }
    int index = Math.floorMod(roomId.hashCode(), STRIPE_COUNT);
    return locks[index];
  }

  /**
   * 지정된 방의 락을 획득한 후 작업을 실행하고 반환값을 리턴한다.
   */
  @Override
  public <T> T executeWithLock(Long roomId, Supplier<T> task) {
    ReentrantLock lock = getLock(roomId);
    requireLockBeforeTransaction(lock);
    lock.lock();
    try {
      return task.get();
    } finally {
      lock.unlock();
    }
  }

  /**
   * 지정된 방의 락을 획득한 후 반환값이 없는 작업을 실행한다.
   */
  @Override
  public void executeWithLock(Long roomId, Runnable task) {
    ReentrantLock lock = getLock(roomId);
    requireLockBeforeTransaction(lock);
    lock.lock();
    try {
      task.run();
    } finally {
      lock.unlock();
    }
  }

  /**
   * 지정된 방의 락이 현재 획득된 상태인지 확인한다.
   */
  public boolean isLocked(Long roomId) {
    return getLock(roomId).isLocked();
  }

  private void requireLockBeforeTransaction(ReentrantLock lock) {
    // 같은 방 잠금을 먼저 잡은 뒤 시작한 트랜잭션에서의 재진입은 허용한다.
    if (TransactionSynchronizationManager.isActualTransactionActive() && !lock.isHeldByCurrentThread()) {
      throw new IllegalStateException("Room lock must be acquired before starting a transaction");
    }
  }
}
