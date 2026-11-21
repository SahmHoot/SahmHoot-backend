package com.sahmhoot.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * 문자열의 UTF-8 바이트 수 상한. 비밀번호는 BCrypt가 72바이트까지만 받으므로 글자 수 규칙과 함께 건다(#108). null은 통과시키고
 * 필수 여부는 {@code @NotBlank}가 맡는다.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = Utf8MaxBytes.Validator.class)
public @interface Utf8MaxBytes {
  int value();

  String message() default "입력값이 너무 깁니다.";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  class Validator implements ConstraintValidator<Utf8MaxBytes, String> {
    private int max;

    @Override
    public void initialize(Utf8MaxBytes annotation) {
      max = annotation.value();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
  }
}
