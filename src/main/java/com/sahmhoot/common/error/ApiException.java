package com.sahmhoot.common.error;

/** 서비스에서 던지면 GlobalExceptionHandler가 05 공통 에러 형식으로 바꾼다. */
public class ApiException extends RuntimeException {

  private final ErrorCode errorCode;

  public ApiException(ErrorCode errorCode) {
    this(errorCode, errorCode.defaultMessage());
  }

  public ApiException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
