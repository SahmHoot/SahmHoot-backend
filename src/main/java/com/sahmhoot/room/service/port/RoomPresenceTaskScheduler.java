package com.sahmhoot.room.service.port;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/** 접속 인원 변경을 모아서 발행할 예약. 퀴즈·STOMP 하트비트 스케줄러와 공유하지 않는다. */
@FunctionalInterface
public interface RoomPresenceTaskScheduler {
  ScheduledFuture<?> schedule(Instant at, Runnable task);
}
