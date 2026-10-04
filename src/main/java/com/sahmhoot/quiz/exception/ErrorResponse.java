package com.sahmhoot.quiz.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 05 API 명세 1절 에러 응답 규격.
 * 성공은 데이터 그대로 반환하며, 에러만 {status, code, message, fieldErrors?} 형태로 반환한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    int status,
    String code,
    String message,
    List<FieldErrorDetail> fieldErrors
) {

  public record FieldErrorDetail(String field, String message) {}

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(
        errorCode.getStatus().value(),
        errorCode.name(),
        errorCode.getMessage(),
        null
    );
  }

  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(
        errorCode.getStatus().value(),
        errorCode.name(),
        message,
        null
    );
  }

  public static ErrorResponse of(ErrorCode errorCode, List<FieldErrorDetail> fieldErrors) {
    return new ErrorResponse(
        errorCode.getStatus().value(),
        errorCode.name(),
        errorCode.getMessage(),
        fieldErrors
    );
  }
}
