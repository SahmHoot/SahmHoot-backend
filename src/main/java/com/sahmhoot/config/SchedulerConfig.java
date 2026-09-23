package com.sahmhoot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** STOMP 하트비트와 퀴즈 예약 작업(자동 마감·다음 문항·결과)이 같이 쓰는 스케줄러. */
@Configuration
public class SchedulerConfig {

  public static final String TASK_SCHEDULER = "taskScheduler";

  @Bean(name = TASK_SCHEDULER)
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("sahmhoot-scheduler-");
    return scheduler;
  }
}
