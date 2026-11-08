package com.sahmhoot.quiz.schedule;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ThreadPoolAnswerTaskScheduler implements AnswerTaskScheduler {
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  public ThreadPoolAnswerTaskScheduler() {
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("answer-scheduler-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
  }

  @Override
  public ScheduledFuture<?> schedule(Instant at, Runnable task) {
    return scheduler.schedule(task, at);
  }

  @PreDestroy
  void shutdown() { scheduler.shutdown(); }
}
