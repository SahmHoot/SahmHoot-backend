package com.sahmhoot.common.lock;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 방 단위 잠금(06 5절). 잠금 64개를 고정해 두고 방 번호로 하나를 고른다. 다른 방이 같은 잠금을 공유할 수 있지만 순서만 늦어질 뿐 결과는 같다.
 *
 * <p>트랜잭션 바깥에서 잡고, 커밋·발행까지 끝낸 뒤 해제한다. {@code @Transactional} 메서드 안에서 잠그지 말 것. 안에서 잠그면 잠금이 먼저 풀리고
 * 커밋이 뒤따라, 다음 요청이 커밋 전 데이터를 읽는다.
 */
@Component
public class RoomLockManager {

  private static final int LOCK_COUNT = 64;

  private final ReentrantLock[] locks = new ReentrantLock[LOCK_COUNT];

  public RoomLockManager() {
    for (int i = 0; i < LOCK_COUNT; i++) {
      locks[i] = new ReentrantLock();
    }
  }

  public <T> T withRoomLock(long roomId, Supplier<T> action) {
    ReentrantLock lock = locks[(int) Math.floorMod(roomId, (long) LOCK_COUNT)];
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }
}
