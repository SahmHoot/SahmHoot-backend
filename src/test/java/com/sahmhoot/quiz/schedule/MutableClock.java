package com.sahmhoot.quiz.schedule;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 테스트가 손으로 넘기는 시계. 정해 둔 시각에 멈춰 있다가 {@link #setTo(Instant)}로만 움직인다.
 * 퀴즈 서비스가 받는 {@code Clock} 자리에 넣어 "제한시간이 지난 뒤" 같은 상황을 기다리지 않고 만든다.
 */
public class MutableClock extends Clock {

  private volatile Instant now;

  public MutableClock(Instant start) {
    this.now = start;
  }

  public void setTo(Instant instant) {
    this.now = instant;
  }

  @Override
  public Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("테스트 시계는 UTC만 쓴다");
  }
}
