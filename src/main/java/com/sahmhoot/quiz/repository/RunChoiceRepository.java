package com.sahmhoot.quiz.repository;

import com.sahmhoot.quiz.entity.RunChoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 실행 선택지(run_choices) 레포지토리.
 */
public interface RunChoiceRepository extends JpaRepository<RunChoice, Long> {

  List<RunChoice> findByRunQuestionIdOrderByOrderNoAsc(Long runQuestionId);

  Optional<RunChoice> findByIdAndRunQuestionId(Long id, Long runQuestionId);

  List<RunChoice> findByRunQuestion_QuizRun_Id(Long quizRunId);

  @Query("SELECT rc FROM RunChoice rc JOIN FETCH rc.runQuestion rq WHERE rq.quizRun.id = :quizRunId ORDER BY rq.orderNo ASC, rc.orderNo ASC")
  List<RunChoice> findByQuizRunIdWithRunQuestion(@Param("quizRunId") Long quizRunId);
}
