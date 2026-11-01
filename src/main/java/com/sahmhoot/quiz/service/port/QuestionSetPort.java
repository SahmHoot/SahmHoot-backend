package com.sahmhoot.quiz.service.port;

/**
 * 4번(QuestionSet - 용빈 담당) 도메인과의 연동을 위한 인터페이스(포트).
 * Question Set 담당이 구현하고 퀴즈가 사용하는 조회·실행 문항 복사 계약.
 * 소유자 권한은 반환된 hostId로 퀴즈 서비스가 검증한다.
 */
public interface QuestionSetPort {

  /**
   * 문제 세트 정보를 조회한다. 존재하지 않으면 QUESTION_SET_NOT_FOUND 예외를 던진다.
   */
  QuestionSetSnapshot getQuestionSet(Long questionSetId);

  /**
   * 문제 세트의 문항과 선택지를 회차의 run_questions, run_choices로 복사한다.
   * 복사된 문항 수를 반환하며, 문항이 없으면 0을 반환한다.
   * 호출자의 트랜잭션에 참여하며, 복사 실패 시 예외를 던져 전체를 롤백한다.
   * 원본 orderNo와 제한 시간을 보존하고 문항은 READY로 생성한다. 이벤트는 발행하지 않는다.
   */
  int copyQuestionsToQuizRun(Long questionSetId, Long quizRunId);
}
