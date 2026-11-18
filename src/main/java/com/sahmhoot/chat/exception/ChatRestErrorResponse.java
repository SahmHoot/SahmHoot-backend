package com.sahmhoot.chat.exception;

import java.util.List;

public record ChatRestErrorResponse(int status, String code, String message, List<FieldError> fieldErrors) {
  public record FieldError(String field, String message) {}
}
