package com.sahmhoot.quiz.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 스레드로 도는 예약 도구를 짧은 시간(0.1~0.3초)으로 확인한다.
 * "실행되지 않았다"는 확인은 예약 시각보다 넉넉히 기다린 뒤에 본다.
 */
class ThreadPoolQuizTaskSchedulerTest {

  private ThreadPoolQuizTaskScheduler scheduler;

  @BeforeEach
  void setUp() {
    scheduler = new ThreadPoolQuizTaskScheduler();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdown();
  }

  @Test
  @DisplayName("예약한 일은 정해진 시각에 실행되고, 끝나면 장부에서 빠진다")
  void runsTaskAtScheduledTime() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);
    Instant runAt = Instant.now().plusMillis(100);

    scheduler.schedule(10L, runAt, done::countDown);
    assertThat(scheduler.pendingCount(10L)).isEqualTo(1);

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(Instant.now()).isAfterOrEqualTo(runAt);
    waitUntilNoPending(10L);
  }

  @Test
  @DisplayName("이미 지난 시각으로 예약하면 바로 실행된다")
  void runsPastTaskImmediately() throws InterruptedException {
    CountDownLatch done = new CountDownLatch(1);

    scheduler.schedule(10L, Instant.now().minusSeconds(1), done::countDown);

    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    waitUntilNoPending(10L);
  }

  @Test
  @DisplayName("실행 전에 cancelAll을 부르면 그 방의 예약은 하나도 실행되지 않는다")
  void cancelAllPreventsExecution() throws InterruptedException {
    AtomicInteger runs = new AtomicInteger();
    scheduler.schedule(10L, Instant.now().plusMillis(200), runs::incrementAndGet);
    scheduler.schedule(10L, Instant.now().plusMillis(300), runs::incrementAndGet);

    scheduler.cancelAll(10L);

    Thread.sleep(500);
    assertThat(runs.get()).isZero();
    assertThat(scheduler.pendingCount(10L)).isZero();
  }

  @Test
  @DisplayName("한 방을 취소해도 다른 방의 예약은 그대로 실행된다")
  void cancelAllOnlyAffectsThatRoom() throws InterruptedException {
    AtomicInteger room10Runs = new AtomicInteger();
    CountDownLatch room20Done = new CountDownLatch(1);
    scheduler.schedule(10L, Instant.now().plusMillis(200), room10Runs::incrementAndGet);
    scheduler.schedule(20L, Instant.now().plusMillis(200), room20Done::countDown);

    scheduler.cancelAll(10L);

    assertThat(room20Done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(room10Runs.get()).isZero();
  }

  @Test
  @DisplayName("예약이 없는 방을 취소해도 아무 일도 없다")
  void cancelAllWithoutTasksDoesNothing() {
    scheduler.cancelAll(99L);

    assertThat(scheduler.pendingCount(99L)).isZero();
  }

  @Test
  @DisplayName("예약된 일이 오류를 내도 장부에서 빠지고, 다음 예약은 정상 실행된다")
  void failingTaskDoesNotStopLaterTasks() throws InterruptedException {
    CountDownLatch next = new CountDownLatch(1);
    scheduler.schedule(10L, Instant.now().plusMillis(50), () -> {
      throw new IllegalStateException("boom");
    });
    scheduler.schedule(10L, Instant.now().plusMillis(150), next::countDown);

    assertThat(next.await(2, TimeUnit.SECONDS)).isTrue();
    waitUntilNoPending(10L);
  }

  /** 실행 직후 장부 정리는 다른 스레드에서 일어나므로 잠깐 기다리며 확인한다. */
  private void waitUntilNoPending(Long roomId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2_000;
    while (scheduler.pendingCount(roomId) > 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertThat(scheduler.pendingCount(roomId)).isZero();
  }
}
