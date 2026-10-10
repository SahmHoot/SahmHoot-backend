package com.sahmhoot.quiz.dto;

import java.util.List;

/**
 * 06 실시간 규격 QUIZ_FINISHED 결과 화면 데이터 DTO.
 * 문항별 정답, 선택지 분포, 응답·정답·오답 수 등을 포함한다.
 */
public record QuizResultResponse(
    List<QuestionResultDetail> questions
) {

  public record QuestionResultDetail(
      Long runQuestionId,
      int questionNo,
      String content,
      Long correctChoiceId,
      int answeredCount,
      int correctCount,
      int incorrectCount,
      List<ChoiceResultDetail> choices
  ) {}

  public record ChoiceResultDetail(
      Long id,
      int orderNo,
      String content,
      int count
  ) {}
}
