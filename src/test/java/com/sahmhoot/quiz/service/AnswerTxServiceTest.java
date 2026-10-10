package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.entity.Answer;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnswerTxServiceTest {

  @Mock
  private AnswerRepository answerRepository;

  @Mock
  private RunQuestionRepository runQuestionRepository;

  @Mock
  private RunChoiceRepository runChoiceRepository;

  private AnswerTxService answerTxService;

  @BeforeEach
  void setUp() {
    answerTxService = new AnswerTxService(
        answerRepository,
        runQuestionRepository,
        runChoiceRepository
    );
  }

  @Test
  @DisplayName("오프라인 참가자의 기존 답이 있어도 접속 중인 전원이 답하기 전에는 마감하지 않는다")
  void submitAnswer_firstTime_savesNewAnswer() {
    Long roomId = 42L;
    Long participantId = 900L;
    Long runQuestionId = 812L;
    Long choiceId = 3001L;

    QuizRun quizRun = QuizRun.builder()
        .id(77L)
        .roomId(roomId)
        .status(QuizRunStatus.RUNNING)
        .build();

    RunQuestion question = RunQuestion.builder()
        .id(runQuestionId)
        .quizRun(quizRun)
        .status(RunQuestionStatus.OPEN)
        .closesAt(Instant.now().plusSeconds(10))
        .build();

    RunChoice choice = RunChoice.builder()
        .id(choiceId)
        .runQuestion(question)
        .content("보기 1")
        .orderNo(1)
        .correct(true)
        .build();

    given(runQuestionRepository.findById(runQuestionId)).willReturn(Optional.of(question));
    given(runChoiceRepository.findByIdAndRunQuestionId(choiceId, runQuestionId)).willReturn(Optional.of(choice));
    given(answerRepository.findByRunQuestionIdAndParticipantId(runQuestionId, participantId))
        .willReturn(Optional.empty());
    given(answerRepository.countByRunQuestionId(runQuestionId)).willReturn(2L);
    given(answerRepository.countByRunQuestionIdAndParticipantIdIn(runQuestionId, Set.of(participantId, 901L)))
        .willReturn(1L);

    SubmitAnswerTxResult result = answerTxService.submitAnswer(
        roomId,
        participantId,
        new SubmitAnswerRequest(runQuestionId, choiceId),
        Set.of(participantId, 901L)
    );

    assertThat(result.runQuestionId()).isEqualTo(runQuestionId);
    assertThat(result.answeredCount()).isEqualTo(2);
    assertThat(result.shouldClose()).isFalse();

    verify(answerRepository).save(any(Answer.class));
  }

  @Test
  @DisplayName("참가자가 기존 답변을 변경하면 수정(updateChoice)하고 덮어쓴다")
  void submitAnswer_existingAnswer_updatesChoice() {
    Long roomId = 42L;
    Long participantId = 900L;
    Long runQuestionId = 812L;
    Long oldChoiceId = 3001L;
    Long newChoiceId = 3002L;

    QuizRun quizRun = QuizRun.builder()
        .id(77L)
        .roomId(roomId)
        .status(QuizRunStatus.RUNNING)
        .build();

    RunQuestion question = RunQuestion.builder()
        .id(runQuestionId)
        .quizRun(quizRun)
        .status(RunQuestionStatus.OPEN)
        .closesAt(Instant.now().plusSeconds(10))
        .build();

    RunChoice oldChoice = RunChoice.builder().id(oldChoiceId).runQuestion(question).build();
    RunChoice newChoice = RunChoice.builder().id(newChoiceId).runQuestion(question).build();

    Answer existingAnswer = Answer.builder()
        .id(501L)
        .runQuestion(question)
        .participantId(participantId)
        .runChoice(oldChoice)
        .submittedAt(Instant.now().minusSeconds(5))
        .updatedAt(Instant.now().minusSeconds(5))
        .build();

    given(runQuestionRepository.findById(runQuestionId)).willReturn(Optional.of(question));
    given(runChoiceRepository.findByIdAndRunQuestionId(newChoiceId, runQuestionId)).willReturn(Optional.of(newChoice));
    given(answerRepository.findByRunQuestionIdAndParticipantId(runQuestionId, participantId))
        .willReturn(Optional.of(existingAnswer));
    given(answerRepository.countByRunQuestionId(runQuestionId)).willReturn(1L);
    given(answerRepository.countByRunQuestionIdAndParticipantIdIn(runQuestionId, Set.of(participantId)))
        .willReturn(1L);

    SubmitAnswerTxResult result = answerTxService.submitAnswer(
        roomId,
        participantId,
        new SubmitAnswerRequest(runQuestionId, newChoiceId),
        Set.of(participantId)
    );

    assertThat(existingAnswer.getRunChoice().getId()).isEqualTo(newChoiceId);
    assertThat(result.shouldClose()).isTrue();
    verify(answerRepository).save(existingAnswer);
  }

  @Test
  @DisplayName("마감된 문항에 제출하면 QUESTION_CLOSED 예외를 던진다")
  void submitAnswer_questionClosed_throwsException() {
    Long roomId = 42L;
    Long participantId = 900L;
    Long runQuestionId = 812L;

    QuizRun quizRun = QuizRun.builder().id(77L).roomId(roomId).status(QuizRunStatus.RUNNING).build();
    RunQuestion question = RunQuestion.builder()
        .id(runQuestionId)
        .quizRun(quizRun)
        .status(RunQuestionStatus.CLOSED)
        .build();

    given(runQuestionRepository.findById(runQuestionId)).willReturn(Optional.of(question));

    assertThatThrownBy(() -> answerTxService.submitAnswer(
        roomId,
        participantId,
        new SubmitAnswerRequest(runQuestionId, 3001L),
        Set.of(participantId)
    ))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.QUESTION_CLOSED);
  }
}
