package com.sahmhoot.quiz.service.port;

import java.util.Optional;

/**
 * 4번(QuestionSet - 용빈 담당) 도메인과의 연동을 위한 인터페이스(포트).
 * 퀴즈가 문제 세트 모듈에게 필요한 일을 적어 둔 목록이며, 실제 코드는 questionset 모듈의
 * {@code QuizQuestionSetAdapter}에 있다. 퀴즈는 이 인터페이스만 알고 세트 모듈 내부는 모른다.
 *
 * <p>세트 모듈은 퀴즈 오류를 던지지 않는다. 세트가 없으면 {@link Optional#empty()}를 돌려주고,
 * 어떤 오류로 응답할지는 퀴즈가 자기 {@code ErrorCode}로 정한다({@link RoomPort}와 같은 원칙).
 *
 * <p>소유자 권한은 반환된 hostId로 퀴즈 서비스가 검증한다.
 */
public interface QuestionSetPort {

  /**
   * 문제 세트 정보를 조회한다. 세트가 없으면 빈 값을 돌려준다.
   */
  Optional<QuestionSetSnapshot> findQuestionSet(Long questionSetId);

  /**
   * 문제 세트의 문항과 선택지를 회차의 run_questions, run_choices로 복사한다.
   * 복사된 문항 수를 반환하며, 문항이 없으면 0을 반환한다.
   * 호출자의 트랜잭션에 참여하며, 복사 실패 시 예외를 던져 전체를 롤백한다.
   * 트랜잭션이 없는 호출은 IllegalTransactionStateException으로 거부한다.
   * 원본 orderNo와 제한 시간을 보존하고 문항은 READY로 생성한다. 이벤트는 발행하지 않는다.
   */
  int copyQuestionsToQuizRun(Long questionSetId, Long quizRunId);
}
