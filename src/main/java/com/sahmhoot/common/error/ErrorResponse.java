package com.sahmhoot.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 05 API 명세의 에러 응답 형식. 성공 응답은 이 형식으로 감싸지 않는다. */
public record ErrorResponse(
    int status,
    String code,
    String message,
    @JsonInclude(JsonInclude.Include.NON_NULL) List<FieldError> fieldErrors) {

  public record FieldError(String field, String message) {}

  public static ErrorResponse of(ErrorCode errorCode) {
    return of(errorCode, errorCode.getMessage());
  }

  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(errorCode.getStatus().value(), errorCode.name(), message, null);
  }

  public static ErrorResponse of(ErrorCode errorCode, List<FieldError> fieldErrors) {
    return new ErrorResponse(
        errorCode.getStatus().value(), errorCode.name(), errorCode.getMessage(), fieldErrors);
  }
}
