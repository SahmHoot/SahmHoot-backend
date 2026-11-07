package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.dto.message.QuestionClosedMessage;
import com.sahmhoot.quiz.dto.message.QuestionOpenedMessage;
import com.sahmhoot.quiz.dto.message.QuizCanceledMessage;
import com.sahmhoot.quiz.dto.message.QuizFinishedMessage;
import com.sahmhoot.quiz.dto.message.RoomWaitingMessage;
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 상태 조회에도 QUESTION_CLOSED와 자동 진행 예약이 쓰는 정확한 시각을 제공한다.
 * 지연 마감 때 closedAt은 closesAt + 1초로 제한되므로, 메모리 값이 있으면 실제 예약 시각을 쓴다.
 * 방 잠금 안에서 커밋된 이벤트만 반영하며, 다음 상태로 넘어가면 방의 항목을 지운다.
 * 예약과 마찬가지로 메모리 상태다. 재시작 후에는 조회와 QuizRecoveryService가 closedAt + 3초를 사용한다.
 */
@Component
public class QuizFeedbackState {
  private final Map<Long, Feedback> byRoom = new ConcurrentHashMap<>();

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void committed(QuizStateChangedEvent event) {
    var message = event.message();
    if (message instanceof QuestionClosedMessage closed) {
      byRoom.put(event.roomId(), new Feedback(closed.runId(), closed.runQuestionId(), closed.nextOpensAt()));
    } else if (message instanceof QuestionOpenedMessage || message instanceof QuizCanceledMessage
        || message instanceof QuizFinishedMessage || message instanceof RoomWaitingMessage) {
      byRoom.remove(event.roomId());
    }
  }

  public Optional<Instant> nextOpensAt(Long roomId, Long runId, Long questionId) {
    return Optional.ofNullable(byRoom.get(roomId))
        .filter(value -> value.runId().equals(runId) && value.questionId().equals(questionId))
        .map(Feedback::nextOpensAt);
  }

  private record Feedback(Long runId, Long questionId, Instant nextOpensAt) {}
}
