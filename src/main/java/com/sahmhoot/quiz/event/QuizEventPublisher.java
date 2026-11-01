package com.sahmhoot.quiz.event;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** DB 커밋이 성공한 메시지만 코디네이터의 방 잠금 안에서 동기 전송한다. */
@Component
@RequiredArgsConstructor
public class QuizEventPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(QuizStateChangedEvent event) {
    messagingTemplate.convertAndSend("/topic/rooms/" + event.roomId() + "/quiz", event.message());
  }
}
