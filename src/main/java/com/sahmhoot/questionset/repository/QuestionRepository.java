package com.sahmhoot.questionset.repository;

import com.sahmhoot.questionset.entity.Question;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, Long> {
  List<Question> findByQuestionSetIdOrderByOrderNoAsc(Long setId);
}
