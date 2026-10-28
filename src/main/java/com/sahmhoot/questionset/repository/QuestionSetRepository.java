package com.sahmhoot.questionset.repository;

import com.sahmhoot.questionset.entity.QuestionSet;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface QuestionSetRepository extends JpaRepository<QuestionSet, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from QuestionSet s where s.id = :id")
  Optional<QuestionSet> findLockedById(@Param("id") Long id);
}
