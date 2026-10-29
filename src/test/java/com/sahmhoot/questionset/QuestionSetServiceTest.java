package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.exception.*;
import com.sahmhoot.questionset.repository.*;
import com.sahmhoot.questionset.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class QuestionSetServiceTest {
  private final QuestionSetRepository sets = mock(QuestionSetRepository.class);
  private final QuestionRepository questions = mock(QuestionRepository.class);
  private final ChoiceRepository choices = mock(ChoiceRepository.class);
  private final QuestionSetQueryRepository query = mock(QuestionSetQueryRepository.class);
  private final QuestionSetService service = new QuestionSetService(sets, questions, choices, query);
  private final QuestionSetCurrentUser student = new QuestionSetCurrentUser(2L, "STUDENT", "학생");

  private QuestionSet set() {
    QuestionSet set = new QuestionSet(1L, "제목", "설명", Visibility.PRIVATE);
    ReflectionTestUtils.setField(set, "id", 7L);
    when(sets.findLockedById(7L)).thenReturn(Optional.of(set));
    when(sets.findById(7L)).thenReturn(Optional.of(set));
    return set;
  }
  private void error(Runnable call, QuestionSetErrorCode code) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(QuestionSetException.class,
        e -> assertThat(e.getCode()).isEqualTo(code));
  }

  @Test void studentCannotCreateOrListMine() {
    error(() -> service.create(student, new CreateQuestionSetRequest("제목", null, null)),
        QuestionSetErrorCode.FORBIDDEN);
    error(() -> service.mine(student), QuestionSetErrorCode.FORBIDDEN);
    verifyNoInteractions(sets, query);
  }

  @Test void ownershipIsRequiredForEveryMutation() {
    set();
    error(() -> service.update(7L, 2L, new UpdateQuestionSetRequest()), QuestionSetErrorCode.FORBIDDEN);
    error(() -> service.delete(7L, 2L), QuestionSetErrorCode.FORBIDDEN);
    verifyNoInteractions(questions, choices, query);
  }

  @Test void missingSetsUseNotFound() {
    error(() -> service.detail(7L, 1L), QuestionSetErrorCode.QUESTION_SET_NOT_FOUND);
    error(() -> service.update(7L, 1L, new UpdateQuestionSetRequest()), QuestionSetErrorCode.QUESTION_SET_NOT_FOUND);
    error(() -> service.delete(7L, 1L), QuestionSetErrorCode.QUESTION_SET_NOT_FOUND);
  }

  @Test void usedSetDoesNotDeleteAnyRows() {
    set();
    when(query.used(7L)).thenReturn(true);
    error(() -> service.delete(7L, 1L), QuestionSetErrorCode.QUESTION_SET_IN_USE);
    verifyNoInteractions(choices, questions);
    verify(sets, never()).delete(any());
  }

  @Test void quizHistoryForeignKeyRaceReturnsConflict() {
    var set = set();
    doThrow(new org.springframework.dao.DataIntegrityViolationException("foreign key",
        new java.sql.SQLException("quiz_runs foreign key constraint", "23000", 1451)))
        .when(sets).delete(set);
    error(() -> service.delete(7L, 1L), QuestionSetErrorCode.QUESTION_SET_IN_USE);
  }

  @Test void unrelatedDatabaseFailuresAreNotReportedAsQuizHistory() {
    var set = set();
    var failure = new org.springframework.dao.DataIntegrityViolationException("unrelated constraint");
    doThrow(failure).when(sets).delete(set);
    assertThatThrownBy(() -> service.delete(7L, 1L)).isSameAs(failure);
  }

  @Test void detailRetainsOwnerOrPublicAccessPolicy() {
    QuestionSet set = set();
    error(() -> service.detail(7L, 2L), QuestionSetErrorCode.FORBIDDEN);
    set.update(set.getTitle(), set.getDescription(), Visibility.PUBLIC);
    when(questions.findByQuestionSetIdOrderByOrderNoAsc(7L)).thenReturn(List.of());
    when(query.ownerName(1L)).thenReturn("교수");
    assertThat(service.detail(7L, 2L).ownerName()).isEqualTo("교수");
  }

  @Test void patchPreservesOmittedFields() {
    QuestionSet set = set();
    var patch = new UpdateQuestionSetRequest();
    patch.readTitle("새 제목");
    service.update(7L, 1L, patch);
    assertThat(set.getTitle()).isEqualTo("새 제목");
    assertThat(set.getDescription()).isEqualTo("설명");
    assertThat(set.getVisibility()).isEqualTo(Visibility.PRIVATE);
  }

  @Test void patchNullDescriptionClearsItAndPreservesRequiredFields() {
    QuestionSet set = set();
    var patch = new UpdateQuestionSetRequest();
    patch.readDescription(null);
    service.update(7L, 1L, patch);
    assertThat(set.getDescription()).isNull();
    assertThat(set.getTitle()).isEqualTo("제목");
    assertThat(set.getVisibility()).isEqualTo(Visibility.PRIVATE);
  }

  @Test void createNullVisibilityDefaultsToPrivateAndPreservesWhitespace() {
    when(sets.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    service.create(new QuestionSetCurrentUser(1L, "PROFESSOR", "교수"),
        new CreateQuestionSetRequest(" 제목 ", " 설명 ", null));
    var captured = org.mockito.ArgumentCaptor.forClass(QuestionSet.class);
    verify(sets).saveAndFlush(captured.capture());
    assertThat(captured.getValue().getVisibility()).isEqualTo(Visibility.PRIVATE);
    assertThat(captured.getValue().getTitle()).isEqualTo(" 제목 ");
    assertThat(captured.getValue().getDescription()).isEqualTo(" 설명 ");
  }
}
