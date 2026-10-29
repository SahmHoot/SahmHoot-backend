package com.sahmhoot.questionset.exception;

import lombok.Getter;

@Getter
public class QuestionSetException extends RuntimeException {
  private final QuestionSetErrorCode code;
  public QuestionSetException(QuestionSetErrorCode code) {
    super(code.message);
    this.code = code;
  }
}
