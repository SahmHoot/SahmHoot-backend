package com.sahmhoot.quiz.repository;

import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 실행 문항(run_questions) 레포지토리.
 */
public interface RunQuestionRepository extends JpaRepository<RunQuestion, Long> {

  List<RunQuestion> findByQuizRunIdOrderByOrderNoAsc(Long quizRunId);

  Optional<RunQuestion> findByQuizRunIdAndOrderNo(Long quizRunId, int orderNo);

  Optional<RunQuestion> findFirstByQuizRunIdAndStatus(Long quizRunId, RunQuestionStatus status);
}
