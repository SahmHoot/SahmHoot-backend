package com.sahmhoot.common.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoomLockManagerTest {

  private final RoomLockManager lockManager = new RoomLockManager();
  private final ExecutorService executor = Executors.newFixedThreadPool(8);

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  void 작업의_반환값을_그대로_돌려준다() {
    assertThat(lockManager.withRoomLock(1L, () -> "done")).isEqualTo("done");
  }

  @Test
  void 같은_방의_작업은_한_번에_하나씩_실행된다() throws Exception {
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxRunning = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();

    for (int i = 0; i < 100; i++) {
      futures.add(
          executor.submit(
              () ->
                  lockManager.withRoomLock(
                      7L,
                      () -> {
                        int now = running.incrementAndGet();
                        maxRunning.accumulateAndGet(now, Math::max);
                        sleepQuietly(1);
                        running.decrementAndGet();
                        return null;
                      })));
    }
    for (Future<?> future : futures) {
      future.get(10, TimeUnit.SECONDS);
    }

    assertThat(maxRunning.get()).isEqualTo(1);
  }

  @Test
  void 다른_방은_서로_막지_않는다() throws Exception {
    CountDownLatch room1Locked = new CountDownLatch(1);
    CountDownLatch releaseRoom1 = new CountDownLatch(1);

    Future<?> holder =
        executor.submit(
            () ->
                lockManager.withRoomLock(
                    1L,
                    () -> {
                      room1Locked.countDown();
                      awaitQuietly(releaseRoom1);
                      return null;
                    }));
    assertThat(room1Locked.await(5, TimeUnit.SECONDS)).isTrue();

    Future<String> otherRoom = executor.submit(() -> lockManager.withRoomLock(2L, () -> "room2"));

    assertThat(otherRoom.get(2, TimeUnit.SECONDS)).isEqualTo("room2");
    releaseRoom1.countDown();
    holder.get(5, TimeUnit.SECONDS);
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
