package com.sahmhoot.quiz.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoomQuizTaskCancellerTest {

  @Test
  @DisplayName("수업 종료 취소가 불리면 그 방의 퀴즈 예약을 모두 취소한다")
  void cancelAll_delegatesToQuizTaskScheduler() {
    QuizTaskScheduler taskScheduler = mock(QuizTaskScheduler.class);
    RoomQuizTaskCanceller canceller = new RoomQuizTaskCanceller(taskScheduler);

    canceller.cancelAll(42L);

    verify(taskScheduler).cancelAll(42L);
    verifyNoMoreInteractions(taskScheduler);
  }
}
