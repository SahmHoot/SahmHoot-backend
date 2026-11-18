package com.sahmhoot.reaction.service;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/** 반응 집계의 지연 전송·만료 반영 예약. 테스트에서는 수동 실행 구현으로 바꾼다. */
public interface ReactionTaskScheduler {
  ScheduledFuture<?> schedule(Instant at, Runnable task);
}
