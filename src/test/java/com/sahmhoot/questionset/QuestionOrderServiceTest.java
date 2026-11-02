package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.questionset.dto.ReorderQuestionsRequest;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.exception.*;
import com.sahmhoot.questionset.repository.*;
import com.sahmhoot.questionset.service.QuestionService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class QuestionOrderServiceTest {
  private final QuestionSetRepository sets = mock(QuestionSetRepository.class);
  private final QuestionRepository questions = mock(QuestionRepository.class);
  private final ChoiceRepository choices = mock(ChoiceRepository.class);
  private final QuestionService service = new QuestionService(sets, questions, choices);
  private final QuestionSet set = new QuestionSet(42L, "세트", null, Visibility.PUBLIC);
  private List<Question> existing;

  @BeforeEach void setup() {
    when(sets.findLockedById(7L)).thenReturn(Optional.of(set));
    existing = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      var q = new Question(set, QuestionType.TRUE_FALSE, "문항 " + i, 15, i);
      ReflectionTestUtils.setField(q, "id", (long) i);
      existing.add(q);
    }
    when(questions.findByQuestionSetIdOrderByOrderNoAsc(7L)).thenReturn(existing);
  }

  @Test void assignsRequestedOrderAndTouchesSet() {
    var before = set.getUpdatedAt();
    service.reorder(7L, 42L, new ReorderQuestionsRequest(List.of(3L, 1L, 2L)));
    assertThat(existing).extracting(Question::getOrderNo).containsExactly(2, 3, 1);
    assertThat(set.getUpdatedAt()).isAfterOrEqualTo(before);
    verify(sets).findLockedById(7L);
    verifyNoInteractions(choices);
  }

  @Test void rejectsMissingDuplicateForeignAndExtraIdsBeforeWriting() {
    var before = set.getUpdatedAt();
    for (var ids : List.of(List.of(1L, 2L), List.of(1L, 1L, 3L),
        List.of(1L, 2L, 99L), List.of(1L, 2L, 3L, 99L), List.<Long>of())) {
      error(() -> service.reorder(7L, 42L, new ReorderQuestionsRequest(ids)),
          QuestionSetErrorCode.ORDER_MISMATCH);
    }
    assertThat(existing).extracting(Question::getOrderNo).containsExactly(1, 2, 3);
    assertThat(set.getUpdatedAt()).isEqualTo(before);
    verify(questions, never()).flush();
  }

  @Test void ownershipIsCheckedBeforeQuestionMembership() {
    error(() -> service.reorder(7L, 9L, new ReorderQuestionsRequest(List.of())),
        QuestionSetErrorCode.FORBIDDEN);
    verifyNoInteractions(questions, choices);
  }

  @Test void missingSetReturnsSetNotFound() {
    when(sets.findLockedById(7L)).thenReturn(Optional.empty());
    error(() -> service.reorder(7L, 42L, new ReorderQuestionsRequest(List.of())),
        QuestionSetErrorCode.QUESTION_SET_NOT_FOUND);
    verifyNoInteractions(questions, choices);
  }

  @Test void emptySetAcceptsEmptyPermutation() {
    when(questions.findByQuestionSetIdOrderByOrderNoAsc(7L)).thenReturn(List.of());
    service.reorder(7L, 42L, new ReorderQuestionsRequest(List.of()));
  }

  private void error(Runnable call, QuestionSetErrorCode code) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(QuestionSetException.class,
        e -> assertThat(e.getCode()).isEqualTo(code));
  }
}
