package com.sahmhoot.questionset.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionSetErrorResponse(int status, String code, String message,
    List<FieldError> fieldErrors) {
  public record FieldError(String field, String message) {}
}
