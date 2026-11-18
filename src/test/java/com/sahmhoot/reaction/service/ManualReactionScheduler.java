package com.sahmhoot.reaction.service;

import com.sahmhoot.quiz.schedule.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 예약을 목록에만 적어 두고 테스트가 직접 실행한다. 취소된 예약도 강제로 실행해 볼 수 있다. */
class ManualReactionScheduler implements ReactionTaskScheduler {
  private final MutableClock clock;
  private final List<Task> tasks = new ArrayList<>();
  private long seq;
  boolean failNext;

  ManualReactionScheduler(MutableClock clock) {
    this.clock = clock;
  }

  @Override
  public ScheduledFuture<?> schedule(Instant at, Runnable action) {
    if (failNext) {
      failNext = false;
      throw new IllegalStateException("예약 실패 주입");
    }
    Task task = new Task(at, seq++, action);
    tasks.add(task);
    return task;
  }

  /** 취소되지 않은 예약 중 가장 이른 것을 시계를 그 시각으로 옮겨 실행한다. */
  Task runNext() {
    Task next = active().stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("실행할 예약이 없다"));
    if (clock.instant().isBefore(next.at)) clock.setTo(next.at);
    run(next);
    return next;
  }

  /**
   * 시계를 움직이지 않고 취소 여부와 관계없이 실행한다.
   * 이미 잠금을 기다리던 콜백이 취소 뒤에 실행되는 상황이다.
   */
  void run(Task task) {
    tasks.remove(task);
    task.action.run();
  }

  List<Task> active() {
    return tasks.stream().filter(task -> !task.cancelled)
        .sorted(Comparator.comparing((Task task) -> task.at).thenComparingLong(task -> task.seq)).toList();
  }

  List<Instant> activeTimes() {
    return active().stream().map(task -> task.at).toList();
  }

  static final class Task implements ScheduledFuture<Object> {
    final Instant at;
    final long seq;
    final Runnable action;
    boolean cancelled;

    Task(Instant at, long seq, Runnable action) {
      this.at = at;
      this.seq = seq;
      this.action = action;
    }

    @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public boolean isDone() { return cancelled; }
    @Override public Object get() { return null; }
    @Override public Object get(long timeout, TimeUnit unit) { return null; }
    @Override public long getDelay(TimeUnit unit) { return 0; }
    @Override public int compareTo(Delayed other) { return 0; }
  }
}
