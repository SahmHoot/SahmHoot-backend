package com.sahmhoot.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

  @Test
  void 한도를_넘으면_거부한다() {
    RateLimiter limiter = new RateLimiter();
    for (int i = 0; i < 20; i++) {
      assertThat(limiter.tryAcquire("join:1", 20, Duration.ofMinutes(1))).isTrue();
    }
    assertThat(limiter.tryAcquire("join:1", 20, Duration.ofMinutes(1))).isFalse();
    assertThat(limiter.tryAcquire("join:2", 20, Duration.ofMinutes(1))).isTrue();
  }
}
