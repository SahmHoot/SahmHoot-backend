package com.sahmhoot.questionset.exception;

import java.util.List;
import lombok.Getter;

@Getter
public class QuestionSetException extends RuntimeException {
  private final QuestionSetErrorCode code;
  private final List<QuestionSetErrorResponse.FieldError> fieldErrors;

  public QuestionSetException(QuestionSetErrorCode code) {
    this(code, null);
  }

  public QuestionSetException(QuestionSetErrorCode code, List<QuestionSetErrorResponse.FieldError> fieldErrors) {
    super(code.message);
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}
