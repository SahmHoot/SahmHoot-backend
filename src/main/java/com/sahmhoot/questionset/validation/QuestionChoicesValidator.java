package com.sahmhoot.questionset.validation;

import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.QuestionType;
import jakarta.validation.*;
import java.util.HashSet;
import java.util.Set;

public class QuestionChoicesValidator implements ConstraintValidator<ValidQuestionChoices, QuestionWriteRequest> {
  @Override
  public boolean isValid(QuestionWriteRequest request, ConstraintValidatorContext context) {
    if (request == null || request.type() == null || request.choices() == null) return true;
    var choices = request.choices();
    boolean valid = choices.size() == (request.type() == QuestionType.MULTIPLE_CHOICE ? 4 : 2)
        && choices.stream().allMatch(c -> c != null && c.isCorrect() != null)
        && choices.stream().filter(c -> c != null && Boolean.TRUE.equals(c.isCorrect())).count() == 1;
    if (valid && request.type() == QuestionType.TRUE_FALSE) {
      Set<String> contents = new HashSet<>();
      choices.forEach(c -> contents.add(c.content()));
      valid = contents.equals(Set.of("O", "X"));
    }
    if (!valid) {
      context.disableDefaultConstraintViolation();
      context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
          .addPropertyNode("choices").addConstraintViolation();
    }
    return valid;
  }
}
