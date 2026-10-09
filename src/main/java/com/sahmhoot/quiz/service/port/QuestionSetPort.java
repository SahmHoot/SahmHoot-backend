package com.sahmhoot.quiz.service.port;

/**
 * 4번(QuestionSet - 용빈 담당) 도메인과의 연동을 위한 인터페이스(포트).
 * 문제 세트 존재 여부 및 소유자 확인을 담당한다.
 */
public interface QuestionSetPort {

  /**
   * 문제 세트 정보를 조회한다. 존재하지 않으면 QUESTION_SET_NOT_FOUND 예외를 던진다.
   */
  QuestionSetSnapshot getQuestionSet(Long questionSetId);

  /**
   * 문제 세트의 문항과 선택지를 회차의 run_questions, run_choices로 복사한다.
   * 복사된 문항 수를 반환하며, 문항이 없으면 0을 반환한다.
   */
  int copyQuestionsToQuizRun(Long questionSetId, Long quizRunId);
}
