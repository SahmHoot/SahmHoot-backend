package com.sahmhoot.quiz.schedule;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

/** 응답 현황의 지연 전송·접속 변동 재확인 예약. 퀴즈 진행 예약의 일괄 취소와 분리한다. */
public interface AnswerTaskScheduler {
  ScheduledFuture<?> schedule(Instant at, Runnable task);
}
