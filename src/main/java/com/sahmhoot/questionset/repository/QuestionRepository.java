package com.sahmhoot.questionset.repository;

import com.sahmhoot.questionset.entity.Question;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, Long> {
  List<Question> findByQuestionSetIdOrderByOrderNoAsc(Long setId);
  Optional<Question> findByIdAndQuestionSetId(Long id, Long setId);
}
