package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.schedule.AnswerTaskScheduler;
import com.sahmhoot.websocket.presence.RoomPresenceChangedEvent;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** #65의 내부 잠금에서는 예약만 등록하고, 접속자 감소의 전원 응답 재확인은 잠금 밖에서 실행한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerPresenceListener {
  private final AnswerTaskScheduler scheduler;
  private final AnswerService answers;
  private final Clock clock;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onPresenceChanged(RoomPresenceChangedEvent event) {
    if (event.ownerConnectionChanged()
        || event.current().onlineUserIds().containsAll(event.previous().onlineUserIds())) return;
    Long roomId = event.current().roomId();
    try {
      scheduler.schedule(clock.instant(), () -> answers.closeIfAllAnswered(roomId));
    } catch (RuntimeException failure) {
      // 같은 동기 알림의 나머지 처리기도 계속 실행되어야 한다.
      log.error("Failed to schedule answer completion check for roomId={}", roomId, failure);
    }
  }
}
