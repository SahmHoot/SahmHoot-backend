package com.sahmhoot.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 공용 빈도 제한 도구(서버 메모리, 슬라이딩 윈도). 서버 1대 전제.
 *
 * <p>예: 입장 코드 조회·입장 사용자당 1분 20회 → {@code tryAcquire("join:" + userId, 20, Duration.ofMinutes(1))}
 */
@Component
public class RateLimiter {

  private final Map<String, Deque<Long>> buckets = new ConcurrentHashMap<>();
  private final Clock clock;

  @Autowired
  public RateLimiter() {
    this(Clock.systemUTC());
  }

  RateLimiter(Clock clock) {
    this.clock = clock;
  }

  /** 허용되면 true를 돌려주고 1회로 기록한다. 초과면 false. */
  public boolean tryAcquire(String key, int limit, Duration window) {
    long now = clock.millis();
    long from = now - window.toMillis();
    Deque<Long> q = buckets.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (q) {
      while (!q.isEmpty() && q.peekFirst() <= from) {
        q.pollFirst();
      }
      if (q.size() >= limit) {
        return false;
      }
      q.addLast(now);
      return true;
    }
  }
}
