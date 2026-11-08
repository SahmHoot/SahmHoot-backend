package com.sahmhoot.room.service;

import com.sahmhoot.room.service.port.RoomPresenceTaskScheduler;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ThreadPoolRoomPresenceScheduler implements RoomPresenceTaskScheduler {
  private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

  public ThreadPoolRoomPresenceScheduler() {
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("room-presence-");
    scheduler.setWaitForTasksToCompleteOnShutdown(false);
    scheduler.initialize();
  }

  @Override public ScheduledFuture<?> schedule(Instant at, Runnable task) {
    return scheduler.schedule(task, at);
  }

  @PreDestroy void shutdown() { scheduler.shutdown(); }
}
