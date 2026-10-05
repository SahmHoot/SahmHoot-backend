package com.sahmhoot.quiz.repository;

import com.sahmhoot.quiz.entity.Answer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 참가자 답변(answers) 레포지토리.
 */
public interface AnswerRepository extends JpaRepository<Answer, Long> {

  Optional<Answer> findByRunQuestionIdAndParticipantId(Long runQuestionId, Long participantId);

  long countByRunQuestionId(Long runQuestionId);

  long countByRunQuestionIdAndParticipantIdIn(Long runQuestionId, Set<Long> participantIds);

  @Query("SELECT a FROM Answer a JOIN FETCH a.runChoice JOIN FETCH a.runQuestion rq WHERE rq.quizRun.id = :quizRunId")
  List<Answer> findByQuizRunIdWithRunChoice(@Param("quizRunId") Long quizRunId);
}
