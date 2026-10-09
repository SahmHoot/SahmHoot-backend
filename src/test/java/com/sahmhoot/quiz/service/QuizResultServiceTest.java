package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.entity.Answer;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizResultServiceTest {

  @Mock
  private QuizRunRepository quizRunRepository;

  @Mock
  private RunQuestionRepository runQuestionRepository;

  @Mock
  private RunChoiceRepository runChoiceRepository;

  @Mock
  private AnswerRepository answerRepository;

  @InjectMocks
  private QuizResultService quizResultService;

  @Test
  @DisplayName("퀴즈 결과 집계 시 fetch join 일괄 조회를 통해 응답 수, 정답 수, 오답 수, 선택지별 분포를 정확히 계산한다 (Issue 9)")
  void aggregateResult_calculatesCountsAccurately() {
    Long runId = 77L;

    QuizRun quizRun = QuizRun.builder()
        .id(runId)
        .roomId(42L)
        .status(QuizRunStatus.FINISHED)
        .build();

    RunQuestion q1 = RunQuestion.builder()
        .id(101L)
        .quizRun(quizRun)
        .orderNo(1)
        .content("문제 1")
        .build();

    RunChoice c1 = RunChoice.builder()
        .id(1001L)
        .runQuestion(q1)
        .orderNo(1)
        .content("보기 1 (정답)")
        .correct(true)
        .build();

    RunChoice c2 = RunChoice.builder()
        .id(1002L)
        .runQuestion(q1)
        .orderNo(2)
        .content("보기 2 (오답)")
        .correct(false)
        .build();

    Answer a1 = Answer.builder().runQuestion(q1).runChoice(c1).participantId(1L).build();
    Answer a2 = Answer.builder().runQuestion(q1).runChoice(c1).participantId(2L).build();
    Answer a3 = Answer.builder().runQuestion(q1).runChoice(c2).participantId(3L).build();

    given(quizRunRepository.findById(runId)).willReturn(Optional.of(quizRun));
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(runId)).willReturn(List.of(q1));
    given(runChoiceRepository.findByQuizRunIdWithRunQuestion(runId)).willReturn(List.of(c1, c2));
    given(answerRepository.findByQuizRunIdWithRunChoice(runId)).willReturn(List.of(a1, a2, a3));

    QuizResultResponse response = quizResultService.aggregateResult(runId);

    assertThat(response.questions()).hasSize(1);

    QuizResultResponse.QuestionResultDetail qDetail = response.questions().getFirst();
    assertThat(qDetail.questionNo()).isEqualTo(1);
    assertThat(qDetail.answeredCount()).isEqualTo(3);
    assertThat(qDetail.correctCount()).isEqualTo(2);
    assertThat(qDetail.incorrectCount()).isEqualTo(1);
    assertThat(qDetail.correctChoiceId()).isEqualTo(1001L);

    QuizResultResponse.ChoiceResultDetail c1Detail = qDetail.choices().getFirst();
    assertThat(c1Detail.id()).isEqualTo(1001L);
    assertThat(c1Detail.count()).isEqualTo(2);

    QuizResultResponse.ChoiceResultDetail c2Detail = qDetail.choices().get(1);
    assertThat(c2Detail.id()).isEqualTo(1002L);
    assertThat(c2Detail.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("문항의 DB orderNo가 비연속적이더라도 questionNo는 1..N 순번으로 매핑된다 (Issue 10)")
  void aggregateResult_nonContiguousOrderNo_producesSequentialQuestionNo() {
    Long runId = 77L;

    QuizRun quizRun = QuizRun.builder()
        .id(runId)
        .roomId(42L)
        .status(QuizRunStatus.FINISHED)
        .build();

    // orderNo가 3, 7로 비연속적인 경우
    RunQuestion q1 = RunQuestion.builder()
        .id(101L)
        .quizRun(quizRun)
        .orderNo(3)
        .content("문제 1")
        .build();

    RunQuestion q2 = RunQuestion.builder()
        .id(102L)
        .quizRun(quizRun)
        .orderNo(7)
        .content("문제 2")
        .build();

    RunChoice c1 = RunChoice.builder().id(1001L).runQuestion(q1).orderNo(1).correct(true).build();
    RunChoice c2 = RunChoice.builder().id(1002L).runQuestion(q2).orderNo(1).correct(true).build();

    given(quizRunRepository.findById(runId)).willReturn(Optional.of(quizRun));
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(runId)).willReturn(List.of(q1, q2));
    given(runChoiceRepository.findByQuizRunIdWithRunQuestion(runId)).willReturn(List.of(c1, c2));
    given(answerRepository.findByQuizRunIdWithRunChoice(runId)).willReturn(List.of());

    QuizResultResponse response = quizResultService.aggregateResult(runId);

    assertThat(response.questions()).hasSize(2);
    // orderNo가 3, 7이어도 questionNo는 1, 2여야 함
    assertThat(response.questions().get(0).questionNo()).isEqualTo(1);
    assertThat(response.questions().get(1).questionNo()).isEqualTo(2);
  }

  @Test
  @DisplayName("응답자가 0명일 때도 집계가 오류 없이 0건으로 정상 처리된다")
  void aggregateResult_zeroRespondents_returnsZeroCounts() {
    Long runId = 77L;

    QuizRun quizRun = QuizRun.builder()
        .id(runId)
        .roomId(42L)
        .status(QuizRunStatus.FINISHED)
        .build();

    RunQuestion q1 = RunQuestion.builder()
        .id(101L)
        .quizRun(quizRun)
        .orderNo(1)
        .content("문제 1")
        .build();

    RunChoice c1 = RunChoice.builder()
        .id(1001L)
        .runQuestion(q1)
        .orderNo(1)
        .content("보기 1 (정답)")
        .correct(true)
        .build();

    given(quizRunRepository.findById(runId)).willReturn(Optional.of(quizRun));
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(runId)).willReturn(List.of(q1));
    given(runChoiceRepository.findByQuizRunIdWithRunQuestion(runId)).willReturn(List.of(c1));
    given(answerRepository.findByQuizRunIdWithRunChoice(runId)).willReturn(List.of());

    QuizResultResponse response = quizResultService.aggregateResult(runId);

    assertThat(response.questions()).hasSize(1);
    QuizResultResponse.QuestionResultDetail qDetail = response.questions().getFirst();
    assertThat(qDetail.answeredCount()).isEqualTo(0);
    assertThat(qDetail.correctCount()).isEqualTo(0);
    assertThat(qDetail.incorrectCount()).isEqualTo(0);
    assertThat(qDetail.choices().getFirst().count()).isEqualTo(0);
  }
}
