package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.entity.Answer;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 5. Answer + Result: 퀴즈 결과 집계 서비스.
 * 퀴즈 회차 종료 시 문항별 정답, 선택지별 응답 수, 정답률/오답 수를 집계한다.
 * 개발 환경 규칙: 상태 조회의 경우 fetch join으로 N+1을 원천 차단한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizResultService {

  private final QuizRunRepository quizRunRepository;
  private final RunQuestionRepository runQuestionRepository;
  private final RunChoiceRepository runChoiceRepository;
  private final AnswerRepository answerRepository;

  /**
   * 퀴즈 회차(runId)의 결과를 집계하여 반환한다.
   * 06 실시간 규격 QUIZ_FINISHED 메시지 생성 및 결과 화면 표시에 사용된다.
   */
  public QuizResultResponse aggregateResult(Long runId) {
    QuizRun quizRun = quizRunRepository.findById(runId)
        .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_RUN_NOT_FOUND));

    List<RunQuestion> questions = runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(quizRun.getId());

    // N+1 방지: 문항별 개별 쿼리가 아닌, 회차 전체의 선택지 및 답변(선택지 fetch join)을 한 번에 일괄 조회
    List<RunChoice> allChoices = runChoiceRepository.findByQuizRunIdWithRunQuestion(quizRun.getId());
    List<Answer> allAnswers = answerRepository.findByQuizRunIdWithRunChoice(quizRun.getId());

    Map<Long, List<RunChoice>> choicesByQuestionId = allChoices.stream()
        .collect(Collectors.groupingBy(rc -> rc.getRunQuestion().getId()));

    Map<Long, List<Answer>> answersByQuestionId = allAnswers.stream()
        .collect(Collectors.groupingBy(a -> a.getRunQuestion().getId()));

    List<QuizResultResponse.QuestionResultDetail> questionDetails = new ArrayList<>();

    int questionNo = 1; // 1..N 순번 보장 (비연속 orderNo 대응)
    for (RunQuestion question : questions) {
      List<RunChoice> choices = choicesByQuestionId.getOrDefault(question.getId(), List.of());
      List<Answer> questionAnswers = answersByQuestionId.getOrDefault(question.getId(), List.of());
      questionDetails.add(buildQuestionResult(question, questionNo++, choices, questionAnswers));
    }

    log.info("Aggregated quiz result for runId={}: total questions={}", runId, questionDetails.size());
    return new QuizResultResponse(questionDetails);
  }

  private QuizResultResponse.QuestionResultDetail buildQuestionResult(
      RunQuestion question,
      int questionNo,
      List<RunChoice> choices,
      List<Answer> answers
  ) {
    Map<Long, Long> choiceCounts = answers.stream()
        .collect(Collectors.groupingBy(a -> a.getRunChoice().getId(), Collectors.counting()));

    Long correctChoiceId = choices.stream()
        .filter(RunChoice::isCorrect)
        .map(RunChoice::getId)
        .findFirst()
        .orElse(null);

    int answeredCount = answers.size();
    int correctCount = (int) answers.stream()
        .filter(a -> a.getRunChoice().isCorrect())
        .count();
    int incorrectCount = answeredCount - correctCount;

    List<QuizResultResponse.ChoiceResultDetail> choiceDetails = choices.stream()
        .map(choice -> new QuizResultResponse.ChoiceResultDetail(
            choice.getId(),
            choice.getOrderNo(),
            choice.getContent(),
            choiceCounts.getOrDefault(choice.getId(), 0L).intValue()
        ))
        .toList();

    return new QuizResultResponse.QuestionResultDetail(
        question.getId(),
        questionNo,
        question.getContent(),
        correctChoiceId,
        answeredCount,
        correctCount,
        incorrectCount,
        choiceDetails
    );
  }
}
