package com.sahmhoot.quiz.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomLockManagerTest {

  private final RoomLockManager lockManager = new RoomLockManager();

  @Test
  @DisplayName("같은 roomId에 대한 작업은 상호 배제(동기화)된다")
  void sameRoomLockSerializesExecution() throws InterruptedException {
    Long roomId = 42L;
    int threadCount = 10;
    int iterationsPerThread = 100;
    AtomicInteger sharedCounter = new AtomicInteger(0);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          for (int j = 0; j < iterationsPerThread; j++) {
            lockManager.executeWithLock(roomId, () -> {
              int current = sharedCounter.get();
              Thread.yield();
              sharedCounter.set(current + 1);
            });
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    boolean completed = doneLatch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(completed).isTrue();
    assertThat(sharedCounter.get()).isEqualTo(threadCount * iterationsPerThread);
  }

  @Test
  @DisplayName("서로 다른 roomId는 동시에 락을 획득할 수 있다")
  void differentRoomCanExecuteConcurrently() throws InterruptedException {
    Long roomA = 100L;
    Long roomB = 101L;

    CountDownLatch aAcquired = new CountDownLatch(1);
    CountDownLatch releaseA = new CountDownLatch(1);
    AtomicBoolean bExecuted = new AtomicBoolean(false);

    Thread t1 = new Thread(() -> {
      lockManager.executeWithLock(roomA, () -> {
        aAcquired.countDown();
        try {
          releaseA.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    });

    Thread t2 = new Thread(() -> {
      try {
        aAcquired.await();
        lockManager.executeWithLock(roomB, () -> {
          bExecuted.set(true);
        });
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    t1.start();
    t2.start();

    t2.join(2000);
    releaseA.countDown();
    t1.join(2000);

    assertThat(bExecuted.get()).isTrue();
  }

  @Test
  @DisplayName("예외가 발생해도 락은 정상적으로 해제된다")
  void lockReleasedOnException() {
    Long roomId = 1L;

    assertThatThrownBy(() ->
        lockManager.executeWithLock(roomId, () -> {
          throw new RuntimeException("Test error");
        })
    ).isInstanceOf(RuntimeException.class);

    // 다시 동일한 roomId 락 획득 가능해야 함
    String result = lockManager.executeWithLock(roomId, () -> "SUCCESS");
    assertThat(result).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("동일 스레드에서 같은 roomId 락을 재진입(Reentrant) 호출할 수 있다")
  void lockIsReentrantOnSameThread() {
    Long roomId = 1L;

    String result = lockManager.executeWithLock(roomId, () ->
        lockManager.executeWithLock(roomId, () -> "INNER_SUCCESS")
    );

    assertThat(result).isEqualTo("INNER_SUCCESS");
  }

  @Test
  @DisplayName("roomId가 null이면 IllegalArgumentException을 던진다")
  void nullRoomIdThrowsException() {
    assertThatThrownBy(() -> lockManager.executeWithLock(null, () -> "FAIL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomId must not be null");

    assertThatThrownBy(() -> lockManager.executeWithLock(null, () -> {}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomId must not be null");
  }
}
