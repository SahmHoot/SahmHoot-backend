package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.room.service.ThreadPoolRoomPresenceScheduler;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class ThreadPoolRoomPresenceSchedulerTest {
  @Test void cancelRemovesThirtyMinuteTaskFromQueueImmediately() {
    var scheduler = new ThreadPoolRoomPresenceScheduler();
    try {
      var delegate = (ThreadPoolTaskScheduler) ReflectionTestUtils.getField(scheduler, "scheduler");
      var executor = delegate.getScheduledThreadPoolExecutor();
      var pending = scheduler.schedule(Instant.now().plusSeconds(1800), () -> {});
      assertThat(executor.getQueue()).hasSize(1);
      assertThat(pending.cancel(false)).isTrue();
      assertThat(executor.getQueue()).isEmpty();
    } finally {
      ReflectionTestUtils.invokeMethod(scheduler, "shutdown");
    }
  }
}
