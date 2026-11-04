package com.sahmhoot.quiz.schedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 테스트용 수동 예약 도구. 예약을 받으면 실행하지 않고 목록에만 적어 두고,
 * 테스트가 {@link #runNext()}를 부를 때 가장 이른 예약을 꺼내 시계를 그 시각으로 옮긴 뒤 실행한다.
 *
 * <p>실행 중에 새로 걸린 예약(다음 문항의 마감 등)은 목록에 이어서 쌓이므로,
 * {@link #runAll()}로 "사람 조작 없이 끝까지 진행"을 확인할 수 있다.
 * 테스트 스레드 하나에서만 쓴다.
 */
public class ManualQuizTaskScheduler implements QuizTaskScheduler {

  /** 예약 한 건. seq는 같은 시각에 걸린 예약을 등록 순서대로 실행하기 위한 번호다. */
  public record Task(Long roomId, Instant runAt, long seq, Runnable action) {
  }

  private final MutableClock clock;
  private final List<Task> pending = new ArrayList<>();
  private long nextSeq;
  private int executedCount;

  public ManualQuizTaskScheduler(MutableClock clock) {
    this.clock = clock;
  }

  @Override
  public synchronized void schedule(Long roomId, Instant runAt, Runnable task) {
    pending.add(new Task(roomId, runAt, nextSeq++, task));
  }

  @Override
  public synchronized void cancelAll(Long roomId) {
    pending.removeIf(task -> task.roomId().equals(roomId));
  }

  /** 가장 이른 예약 하나를 실행한다. 시계가 그 시각보다 뒤에 있으면 시계는 그대로 둔다(늦게 실행된 상황). */
  public Task runNext() {
    Task next;
    synchronized (this) {
      next = pending.stream()
          .min(Comparator.comparing(Task::runAt).thenComparingLong(Task::seq))
          .orElseThrow(() -> new IllegalStateException("실행할 예약이 없다"));
      pending.remove(next);
    }
    if (clock.instant().isBefore(next.runAt())) {
      clock.setTo(next.runAt());
    }
    executedCount++;
    next.action().run();
    return next;
  }

  /** 예약이 없을 때까지 실행한다. 끝없이 이어지는 버그에 대비해 횟수를 제한한다. */
  public void runAll() {
    for (int i = 0; i < 100 && pendingCount() > 0; i++) {
      runNext();
    }
    if (pendingCount() > 0) {
      throw new IllegalStateException("예약이 끝나지 않는다: " + pending);
    }
  }

  public synchronized int pendingCount() {
    return pending.size();
  }

  public synchronized int pendingCount(Long roomId) {
    return (int) pending.stream().filter(task -> task.roomId().equals(roomId)).count();
  }

  /** 남은 예약의 실행 시각들(이른 순). */
  public synchronized List<Instant> pendingTimes() {
    return pending.stream().sorted(Comparator.comparing(Task::runAt).thenComparingLong(Task::seq))
        .map(Task::runAt).toList();
  }

  public int executedCount() {
    return executedCount;
  }

  /** 테스트 사이에 목록과 횟수를 비운다. */
  public synchronized void reset() {
    pending.clear();
    executedCount = 0;
  }
}
