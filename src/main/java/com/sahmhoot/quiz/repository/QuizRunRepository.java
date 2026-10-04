package com.sahmhoot.quiz.repository;

import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 퀴즈 실행(quiz_runs) 레포지토리.
 */
public interface QuizRunRepository extends JpaRepository<QuizRun, Long> {

  Optional<QuizRun> findByIdAndRoomId(Long id, Long roomId);

  Optional<QuizRun> findFirstByRoomIdAndStatus(Long roomId, QuizRunStatus status);

  Optional<QuizRun> findTopByRoomIdOrderByIdDesc(Long roomId);

  boolean existsByRoomIdAndStatus(Long roomId, QuizRunStatus status);
}
