package com.sahmhoot.questionset.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = QuestionChoicesValidator.class)
public @interface ValidQuestionChoices {
  String message() default "유형에 맞는 선택지와 정확히 하나의 정답이 필요합니다.";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}
