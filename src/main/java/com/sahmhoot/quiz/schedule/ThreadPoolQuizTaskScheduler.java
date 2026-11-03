package com.sahmhoot.quiz.schedule;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/**
 * {@link QuizTaskScheduler}의 운영 구현. 스프링 {@link ThreadPoolTaskScheduler}로 시각에 맞춰 실행하고,
 * 방 번호별 "장부"에 걸린 예약을 기록해 두었다가 방 단위로 취소한다.
 *
 * <p>스케줄러는 이 클래스 안에서만 만들고 스프링 빈으로 공개하지 않는다. {@code WebSocketConfig}의
 * STOMP 하트비트 스케줄러와 섞이지 않게 하기 위해서다.
 *
 * <p>예약은 메모리에만 있으므로 서버를 재시작하면 사라진다. 재시작 후 복구는 #25 범위다.
 */
@Slf4j
@Component
public class ThreadPoolQuizTaskScheduler implements QuizTaskScheduler {

  /** 동시에 실행될 예약 수. 예약된 일은 방 잠금 안에서 짧게 끝나므로 작게 둔다. */
  private static final int POOL_SIZE = 4;

  private final ThreadPoolTaskScheduler scheduler;

  /** 장부: 방 번호 → 그 방에 걸려 있고 아직 끝나지 않은 예약들. 여러 스레드가 동시에 건드려도 안전한 자료구조를 쓴다. */
  private final Map<Long, Set<ScheduledFuture<?>>> tasksByRoom = new ConcurrentHashMap<>();

  public ThreadPoolQuizTaskScheduler() {
    scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(POOL_SIZE);
    scheduler.setThreadNamePrefix("quiz-scheduler-");
    // 서버 종료 시 아직 오지 않은 예약은 버린다. 남은 퀴즈 진행은 재시작 복구(#25)가 맡는다.
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
  }

  @Override
  public void schedule(Long roomId, Instant runAt, Runnable task) {
    if (roomId == null) {
      throw new IllegalArgumentException("roomId must not be null");
    }

    // 실행이 끝난 예약은 장부에서 스스로 지운다. 자기 자신(future)을 알아야 해서 등록 뒤에 채워 넣는다.
    AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
    Runnable wrapped = () -> {
      try {
        task.run();
      } catch (RuntimeException e) {
        // 한 방의 오류로 스케줄러 스레드가 죽거나 다른 방 예약이 멈추면 안 되므로 로그만 남긴다.
        log.error("Scheduled quiz task failed for roomId={}", roomId, e);
      } finally {
        remove(roomId, self);
      }
    };

    // 장부 기록과 등록을 방 단위로 묶어, 같은 방의 cancelAll과 엇갈려 장부에서 빠지는 예약이 없게 한다.
    tasksByRoom.compute(roomId, (id, tasks) -> {
      Set<ScheduledFuture<?>> roomTasks = tasks != null ? tasks : ConcurrentHashMap.newKeySet();
      ScheduledFuture<?> future = scheduler.schedule(wrapped, runAt);
      self.set(future);
      roomTasks.add(future);
      return roomTasks;
    });
  }

  @Override
  public void cancelAll(Long roomId) {
    Set<ScheduledFuture<?>> tasks = tasksByRoom.remove(roomId);
    if (tasks == null) {
      return;
    }
    // false: 이미 실행 중인 예약은 중간에 끊지 않는다. 실행 중인 쪽은 DB 상태를 보고 스스로 무시한다.
    tasks.forEach(future -> future.cancel(false));
    log.info("Canceled {} scheduled quiz task(s) for roomId={}", tasks.size(), roomId);
  }

  /** 테스트용: 그 방에 남아 있는(아직 실행·취소되지 않은) 예약 수. */
  int pendingCount(Long roomId) {
    Set<ScheduledFuture<?>> tasks = tasksByRoom.get(roomId);
    return tasks == null ? 0 : tasks.size();
  }

  private void remove(Long roomId, AtomicReference<ScheduledFuture<?>> self) {
    // 지난 시각의 예약은 등록 직후 바로 실행될 수 있다. self는 같은 방 칸을 잡은 상태에서 읽어야
    // schedule()의 compute가 채워 넣은 값을 확실히 본다.
    // 방의 마지막 예약이 빠지면 장부 칸도 지워, 끝난 방이 메모리에 계속 남지 않게 한다.
    tasksByRoom.computeIfPresent(roomId, (id, tasks) -> {
      tasks.remove(self.get());
      return tasks.isEmpty() ? null : tasks;
    });
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdown();
  }
}
