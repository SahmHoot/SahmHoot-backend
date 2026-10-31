package com.sahmhoot.questionset;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.QuestionType;
import jakarta.validation.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QuestionWriteRequestValidationTest {
  private static ValidatorFactory factory;
  private static Validator validator;
  @BeforeAll static void setup() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }
  @AfterAll static void close() { factory.close(); }

  static QuestionWriteRequest ox(Integer seconds) {
    return new QuestionWriteRequest(QuestionType.TRUE_FALSE, "문항", seconds,
        List.of(new ChoiceRequest("O", true), new ChoiceRequest("X", false)));
  }

  @Test void defaultsAndBoundaries() {
    assertThat(validator.validate(ox(null))).isEmpty();
    assertThat(ox(null).secondsOrDefault()).isEqualTo(60);
    assertThat(validator.validate(ox(10))).isEmpty();
    assertThat(validator.validate(ox(300))).isEmpty();
  }

  @ParameterizedTest @ValueSource(ints = {-1, 0, 9, 301})
  void invalidTime(int seconds) { assertThat(validator.validate(ox(seconds))).isNotEmpty(); }

  @Test void choiceCountsAndExactlyOneCorrect() {
    var four = List.of(new ChoiceRequest("A", true), new ChoiceRequest("B", false),
        new ChoiceRequest("C", false), new ChoiceRequest("D", false));
    assertThat(validator.validate(new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE,
        "문항", 60, four))).isEmpty();
    for (var choices : List.of(List.<ChoiceRequest>of(), four.subList(0, 3),
        List.of(new ChoiceRequest("A", false), new ChoiceRequest("B", false),
            new ChoiceRequest("C", false), new ChoiceRequest("D", false)),
        List.of(new ChoiceRequest("A", true), new ChoiceRequest("B", true),
            new ChoiceRequest("C", false), new ChoiceRequest("D", false)))) {
      assertThat(validator.validate(new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE,
          "문항", 60, choices))).isNotEmpty();
    }
    for (var choices : List.of(four.subList(0, 3),
        List.of(new ChoiceRequest("O", false), new ChoiceRequest("X", false)),
        List.of(new ChoiceRequest("O", true), new ChoiceRequest("X", true)),
        List.of(new ChoiceRequest("O", true), new ChoiceRequest("O", false)),
        List.of(new ChoiceRequest("true", true), new ChoiceRequest("false", false)))) {
      assertThat(validator.validate(new QuestionWriteRequest(QuestionType.TRUE_FALSE,
          "문항", 60, choices))).isNotEmpty();
    }
  }

  @Test void nestedNullAndTextLimits() {
    var good = ox(60);
    assertThat(validator.validate(new QuestionWriteRequest(good.type(), "가".repeat(500),
        60, good.choices()))).isEmpty();
    for (String text : List.of("", " ", "가".repeat(501))) {
      assertThat(validator.validate(new QuestionWriteRequest(good.type(), text, 60, good.choices()))).isNotEmpty();
    }
    for (String text : List.of("", " ", "가".repeat(201))) {
      var choices = List.of(new ChoiceRequest(text, true), new ChoiceRequest("B", false),
          new ChoiceRequest("C", false), new ChoiceRequest("D", false));
      assertThat(validator.validate(new QuestionWriteRequest(QuestionType.MULTIPLE_CHOICE,
          "문항", 60, choices))).isNotEmpty();
    }
    assertThat(validator.validate(new QuestionWriteRequest(good.type(), "문항", 60,
        Arrays.asList(null, new ChoiceRequest("X", false))))).isNotEmpty();
    assertThat(validator.validate(new QuestionWriteRequest(good.type(), "문항", 60,
        List.of(new ChoiceRequest("O", null), new ChoiceRequest("X", false))))).isNotEmpty();
  }
}
