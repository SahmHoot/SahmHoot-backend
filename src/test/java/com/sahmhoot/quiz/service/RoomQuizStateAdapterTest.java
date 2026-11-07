package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.message.QuestionClosedMessage;
import com.sahmhoot.quiz.entity.*;
import com.sahmhoot.quiz.event.QuizStateChangedEvent;
import com.sahmhoot.quiz.repository.*;
import com.sahmhoot.quiz.service.port.*;
import com.sahmhoot.room.dto.RoomStateResponse.Phase;
import com.sahmhoot.room.dto.RoomStateResponse.RunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RoomQuizStateAdapterTest {
  private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  private final QuizRunRepository runs = mock(QuizRunRepository.class);
  private final RunQuestionRepository questions = mock(RunQuestionRepository.class);
  private final RunChoiceRepository choices = mock(RunChoiceRepository.class);
  private final AnswerRepository answers = mock(AnswerRepository.class);
  private final QuestionSetPort sets = mock(QuestionSetPort.class);
  private final RoomPort rooms = mock(RoomPort.class);
  private final QuizResultService results = mock(QuizResultService.class);
  private final QuizFeedbackState feedback = new QuizFeedbackState();
  private final RoomQuizStateAdapter adapter = new RoomQuizStateAdapter(
      runs, questions, choices, answers, sets, rooms, results, feedback);
  private QuizRun run;
  private RunQuestion first;
  private RunQuestion second;

  @BeforeEach void setup() {
    run = QuizRun.builder().id(77L).roomId(42L).questionSetId(7L).status(QuizRunStatus.RUNNING).build();
    first = RunQuestion.builder().id(810L).quizRun(run).orderNo(10).status(RunQuestionStatus.CLOSED).build();
    second = RunQuestion.builder().id(812L).quizRun(run).orderNo(30).type(QuestionType.MULTIPLE_CHOICE)
        .content("둘째 문항").timeLimitSeconds(15).status(RunQuestionStatus.OPEN)
        .openedAt(NOW).closesAt(NOW.plusSeconds(15)).build();
    var correct = RunChoice.builder().id(3001L).runQuestion(second).orderNo(1).content("정답").correct(true).build();
    when(runs.findTopByRoomIdOrderByIdDesc(42L)).thenReturn(Optional.of(run));
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new RoomSnapshot(42L, 1L, RoomStatus.PLAYING)));
    when(sets.findQuestionSet(7L)).thenReturn(Optional.of(new QuestionSetSnapshot(7L, 1L, "문제집")));
    when(questions.findByQuizRunIdOrderByOrderNoAsc(77L)).thenReturn(List.of(first, second));
    when(choices.findByRunQuestionIdOrderByOrderNoAsc(812L)).thenReturn(List.of(correct));
    when(answers.findByQuizRunIdAndParticipantId(77L, 900L)).thenReturn(List.of(
        Answer.builder().runQuestion(second).runChoice(correct).participantId(900L).build()));
  }

  @Test void noRunReturnsWaitingWithoutReadingQuestionsOrAnswers() {
    when(runs.findTopByRoomIdOrderByIdDesc(42L)).thenReturn(Optional.empty());
    var state = adapter.read(42L, 900L);
    assertThat(state.quiz()).isNull();
    assertThat(state.quizRun()).isNull();
    verifyNoInteractions(rooms, sets, questions, choices, answers, results);
  }

  @Test void openQuestionUsesSequentialQuestionNumberAndOnlyRequestedParticipantsAnswers() {
    when(answers.countByRunQuestionId(812L)).thenReturn(2L);
    var state = adapter.read(42L, 900L);
    assertThat(state.quiz().phase()).isEqualTo(Phase.QUESTION);
    assertThat(state.quiz().currentQuestion().questionNo()).isEqualTo(2);
    assertThat(state.quiz().currentQuestion().correctChoiceId()).isNull();
    assertThat(state.quiz().myAnswers()).singleElement().satisfies(answer -> {
      assertThat(answer.runQuestionId()).isEqualTo(812L);
      assertThat(answer.choiceId()).isEqualTo(3001L);
    });
    assertThat(state.progress().answeredCount()).isEqualTo(2);
    assertThat(state.quiz().result()).isNull();
    verify(answers).findByQuizRunIdAndParticipantId(77L, 900L);
    verify(answers, never()).findByQuizRunIdWithRunChoice(anyLong());
    verifyNoInteractions(results);
  }

  @Test void feedbackReadsEventDeadlineInsteadOfAddingThreeSecondsToStoredClose() {
    second.close(NOW.plusSeconds(16));
    feedback.committed(new QuizStateChangedEvent(42L,
        QuestionClosedMessage.of(77L, 812L, 3001L, true, NOW.plusSeconds(24), NOW.plusSeconds(21))));
    var state = adapter.read(42L, 900L);
    assertThat(state.quiz().phase()).isEqualTo(Phase.FEEDBACK);
    assertThat(state.quiz().currentQuestion().correctChoiceId()).isEqualTo(3001L);
    assertThat(state.quiz().nextOpensAt()).isEqualTo(NOW.plusSeconds(24));
    assertThat(state.quiz().result()).isNull();
    assertThat(state.progress()).isNull();
    verifyNoInteractions(results);
  }

  @Test void onlyFinishedRunAggregatesResults() {
    run.finish(NOW);
    when(results.aggregateResult(77L)).thenReturn(new QuizResultResponse(List.of(
        new QuizResultResponse.QuestionResultDetail(812L, 2, "둘째 문항", 3001L, 2, 1, 1,
            List.of(new QuizResultResponse.ChoiceResultDetail(3001L, 1, "정답", 1))))));
    var state = adapter.read(42L, 900L);
    assertThat(state.quiz().phase()).isEqualTo(Phase.RESULT);
    assertThat(state.quiz().result().questions().getFirst().choices().getFirst().count()).isEqualTo(1);
    assertThat(state.quiz().currentQuestion()).isNull();
    verify(results).aggregateResult(77L);
  }

  @ParameterizedTest @EnumSource(value = QuizRunStatus.class, names = {"READY", "ABORTED"})
  void inactiveLatestRunDoesNotResurrectOldQuiz(QuizRunStatus status) {
    run = QuizRun.builder().id(78L).roomId(42L).questionSetId(7L).status(status).build();
    when(runs.findTopByRoomIdOrderByIdDesc(42L)).thenReturn(Optional.of(run));
    var state = adapter.read(42L, 900L);
    assertThat(state.quizRun().status()).isEqualTo(RunStatus.valueOf(status.name()));
    assertThat(state.quiz()).isNull();
    verifyNoInteractions(questions, choices, answers, results);
  }

  @Test void resultClosedToWaitingDoesNotReadOrExposeOldResult() {
    run.finish(NOW);
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new RoomSnapshot(42L, 1L, RoomStatus.OPEN)));
    assertThat(adapter.read(42L, 900L).quiz()).isNull();
    verifyNoInteractions(questions, choices, answers, results);
  }
}
