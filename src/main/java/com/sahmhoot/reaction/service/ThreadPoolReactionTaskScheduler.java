package com.sahmhoot.reaction.service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ThreadPoolReactionTaskScheduler implements ReactionTaskScheduler {
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  public ThreadPoolReactionTaskScheduler() {
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("reaction-scheduler-");
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
