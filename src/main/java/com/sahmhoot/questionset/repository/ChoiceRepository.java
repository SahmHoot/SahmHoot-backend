package com.sahmhoot.questionset.repository;

import com.sahmhoot.questionset.entity.Choice;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChoiceRepository extends JpaRepository<Choice, Long> {
  List<Choice> findByQuestionIdInOrderByOrderNoAsc(Collection<Long> questionIds);
  List<Choice> findByQuestionQuestionSetId(Long setId);

  default void deleteForSet(Long id) {
    deleteAll(findByQuestionQuestionSetId(id));
  }
}
