package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sahmhoot.questionset.dto.QuestionWriteRequest;
import com.sahmhoot.questionset.entity.Question;
import com.sahmhoot.questionset.entity.QuestionSet;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.exception.QuestionSetErrorCode;
import com.sahmhoot.questionset.exception.QuestionSetException;
import com.sahmhoot.questionset.repository.ChoiceRepository;
import com.sahmhoot.questionset.repository.QuestionRepository;
import com.sahmhoot.questionset.repository.QuestionSetRepository;
import com.sahmhoot.questionset.service.QuestionService;
import com.sahmhoot.questionset.service.port.QuestionImageAccess;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class QuestionServiceImageTest {
  private final QuestionSetRepository sets = mock(QuestionSetRepository.class);
  private final QuestionRepository questions = mock(QuestionRepository.class);
  private final ChoiceRepository choices = mock(ChoiceRepository.class);
  private final QuestionImageAccess images = mock(QuestionImageAccess.class);
  private final QuestionService service = new QuestionService(sets, questions, choices, images);
  private final QuestionSet set = new QuestionSet(42L, "Set", null, Visibility.PUBLIC);
  private final Question question = new Question(set, QuestionType.TRUE_FALSE, "Original", 15, 1);
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(question, "id", 101L);
    when(sets.findLockedById(7L)).thenReturn(Optional.of(set));
    when(questions.findByIdAndQuestionSetId(101L, 7L)).thenReturn(Optional.of(question));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", ",\"imageRef\":null"})
  void disabledWritesPreserveImageReference(String field) {
    when(choices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(questions.saveAndFlush(any())).thenAnswer(invocation -> {
      Question added = invocation.getArgument(0);
      ReflectionTestUtils.setField(added, "id", 102L);
      return added;
    });
    var request = request(field);

    assertThat(service.add(7L, 42L, request).id()).isEqualTo(102L);
    assertThat(service.update(7L, 101L, 42L, request).content()).isEqualTo("Updated");
    verify(images, times(4)).enabled();
    verifyNoMoreInteractions(images);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"09f13f08-b33f-4dd0-ba2f-5b2e79594666\"", "false", "{}", "1", "[]", "\"\""})
  void disabledUpdateRejectsAnyNonNullImageReference(String value) {
    var request = request(",\"imageRef\":" + value);

    assertThatThrownBy(() -> service.update(7L, 101L, 42L, request))
        .isInstanceOfSatisfying(QuestionSetException.class,
            error -> assertThat(error.getCode()).isEqualTo(QuestionSetErrorCode.VALIDATION_FAILED));
    assertThat(question.getContent()).isEqualTo("Original");
    verify(images).enabled();
    verifyNoMoreInteractions(images);
  }

  @Test
  void enabledUpdateStillDetachesOnlyForExplicitNull() {
    when(images.enabled()).thenReturn(true);
    when(choices.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.update(7L, 101L, 42L, request(""));
    verify(images, never()).detach(7L, 101L);
    service.update(7L, 101L, 42L, request(",\"imageRef\":null"));
    verify(images).detach(7L, 101L);
  }

  private QuestionWriteRequest request(String imageField) {
    return mapper.readValue("""
        {"type":"TRUE_FALSE","content":"Updated","choices":[
          {"content":"O","isCorrect":true},{"content":"X","isCorrect":false}]%s}
        """.formatted(imageField), QuestionWriteRequest.class);
  }
}
