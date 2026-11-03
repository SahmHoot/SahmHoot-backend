package com.sahmhoot.quiz.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 퀴즈 모듈이 "지금 몇 시인지"를 물어볼 시계를 등록한다.
 *
 * <p>퀴즈 서비스는 {@code Instant.now()}로 컴퓨터 시각을 직접 읽지 않고, 여기서 등록한 {@link Clock}을
 * 생성자로 받아 {@code Instant.now(clock)}으로 읽는다. 운영에서는 진짜 시계(UTC)가 들어가므로 동작은
 * 그대로이고, 테스트에서는 특정 시각에 멈춘 가짜 시계를 넣어 "제한시간 + 1초에 마감" 같은 시점을 기다리지
 * 않고 정확히 확인할 수 있다(#23).
 *
 * <p>공용 {@code config/} 대신 퀴즈 모듈 안에 둔다. 나중에 공용 Clock 빈이 생기면 하나로 합친다
 * (Clock 빈이 두 개가 되면 어느 쪽을 넣을지 몰라 앱이 뜨지 않는다).
 */
@Configuration
public class QuizClockConfig {

  @Bean
  public Clock quizClock() {
    return Clock.systemUTC();
  }
}
