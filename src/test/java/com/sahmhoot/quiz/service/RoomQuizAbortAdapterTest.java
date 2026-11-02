package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.dto.message.QuizCanceledMessage;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class RoomQuizAbortAdapterTest {
  private final QuizRunRepository runs = mock(QuizRunRepository.class);
  private final RunQuestionRepository questions = mock(RunQuestionRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final RoomQuizAbortAdapter adapter = new RoomQuizAbortAdapter(runs, questions, events);

  @Test void noQuizDoesNotPublishCancellation() {
    when(runs.findTopByRoomIdOrderByIdDesc(42L)).thenReturn(Optional.empty());
    adapter.abortActiveQuiz(42L);
    verifyNoInteractions(questions, events);
  }

  @ParameterizedTest
  @EnumSource(value = QuizRunStatus.class, names = "RUNNING", mode = EnumSource.Mode.EXCLUDE)
  void inactiveQuizIsPreserved(QuizRunStatus status) {
    QuizRun run = QuizRun.builder().id(77L).roomId(42L).status(status).build();
    when(runs.findTopByRoomIdOrderByIdDesc(42L)).thenReturn(Optional.of(run));
    adapter.abortActiveQuiz(42L);
    assertThat(run.getStatus()).isEqualTo(status);
    verify(runs, never()).save(any());
    verifyNoInteractions(questions, events);
  }

  @Test void abortsRunningQuizAndClosesQuestionAtTheSameTime() {
    QuizRun run = runningQuiz();
    RunQuestion question = RunQuestion.builder().id(810L).quizRun(run)
        .status(RunQuestionStatus.OPEN).build();
    when(questions.findFirstByQuizRunIdAndStatus(77L, RunQuestionStatus.OPEN))
        .thenReturn(Optional.of(question));

    adapter.abortActiveQuiz(42L);

    assertThat(run.isAborted()).isTrue();
    assertThat(question.getStatus()).isEqualTo(RunQuestionStatus.CLOSED);
    assertThat(question.getClosedAt()).isEqualTo(run.getFinishedAt());
    verify(runs).save(run);
    verify(questions).save(question);
    assertCancellation(run);
  }

  @Test void abortsFeedbackPhaseWithoutAnOpenQuestion() {
    QuizRun run = runningQuiz();
    when(questions.findFirstByQuizRunIdAndStatus(77L, RunQuestionStatus.OPEN))
        .thenReturn(Optional.empty());
    adapter.abortActiveQuiz(42L);
    assertThat(run.isAborted()).isTrue();
    verify(questions, never()).save(any());
    assertCancellation(run);
  }

  private QuizRun runningQuiz() {
    QuizRun run = QuizRun.builder().id(77L).roomId(42L).status(QuizRunStatus.RUNNING).build();
    when(runs.findTopByRoomIdOrderByIdDesc(42L)).thenReturn(Optional.of(run));
    return run;
  }

  private void assertCancellation(QuizRun run) {
    var event = ArgumentCaptor.forClass(QuizStateChangedEvent.class);
    verify(events).publishEvent(event.capture());
    assertThat(event.getValue().roomId()).isEqualTo(42L);
    assertThat(event.getValue().message()).isInstanceOfSatisfying(QuizCanceledMessage.class,
        message -> {
          assertThat(message.runId()).isEqualTo(run.getId());
          assertThat(message.sentAt()).isEqualTo(run.getFinishedAt());
        });
  }
}
