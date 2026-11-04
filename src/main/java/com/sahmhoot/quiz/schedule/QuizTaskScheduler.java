package com.sahmhoot.quiz.schedule;

import java.time.Instant;

/**
 * 방 단위로 "정해진 시각에 이 일을 해 줘"를 예약하고, 방의 예약을 한꺼번에 취소하는 도구(#23).
 *
 * <p>퀴즈 서비스는 이 인터페이스만 안다. 운영에서는 {@link ThreadPoolQuizTaskScheduler}가 들어가고,
 * 테스트에서는 시각을 직접 넘기며 실행하는 가짜 구현을 넣는다.
 *
 * <p>이 도구는 잠금·트랜잭션을 다루지 않는다. 예약된 일은 {@code QuizCoreService}의 메서드를 부르고,
 * 그 메서드가 "방 잠금 → 트랜잭션 → DB 상태 재확인"을 한다. 그래서 늦게 실행되거나 취소가 한발 늦은
 * 예약도 DB를 보고 아무것도 하지 않는다.
 */
public interface QuizTaskScheduler {

  /**
   * {@code runAt} 시각에 {@code task}를 한 번 실행하도록 예약한다. 이미 지난 시각이면 바로 실행된다.
   * 예약은 {@code roomId}에 묶여 {@link #cancelAll(Long)}로 함께 취소된다.
   */
  void schedule(Long roomId, Instant runAt, Runnable task);

  /**
   * 그 방에 걸려 있고 아직 실행되지 않은 예약을 모두 취소한다. 예약이 없으면 아무것도 하지 않는다.
   * 이미 실행 중인 예약은 멈추지 않는다(실행되는 쪽이 DB 상태를 보고 스스로 무시한다).
   */
  void cancelAll(Long roomId);
}
