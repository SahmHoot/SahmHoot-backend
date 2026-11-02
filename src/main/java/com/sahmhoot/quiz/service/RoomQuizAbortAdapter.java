package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.dto.message.QuizCanceledMessage;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.room.service.port.RoomQuizAbortPort;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 방 종료 트랜잭션에 참여하며, 일반 퀴즈 중단과 달리 방을 대기로 복귀시키지 않는다. */
@Service
@RequiredArgsConstructor
public class RoomQuizAbortAdapter implements RoomQuizAbortPort {
  private final QuizRunRepository runs;
  private final RunQuestionRepository questions;
  private final ApplicationEventPublisher events;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void abortActiveQuiz(Long roomId) {
    runs.findTopByRoomIdOrderByIdDesc(roomId).filter(QuizRun::isRunning).ifPresent(run -> {
      Instant now = Instant.now();
      run.abort(now);
      runs.save(run);
      questions.findFirstByQuizRunIdAndStatus(run.getId(), RunQuestionStatus.OPEN)
          .ifPresent(question -> {
            question.close(now);
            questions.save(question);
          });
      events.publishEvent(new QuizStateChangedEvent(roomId, QuizCanceledMessage.of(run.getId(), now)));
    });
  }
}
