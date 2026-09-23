package com.sahmhoot.common.error;

/** 서비스에서 {@link ErrorCode}로 실패를 알릴 때 던진다. {@link GlobalExceptionHandler}가 응답으로 바꾼다. */
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }

  /** 기본 메시지 대신 다른 문구를 보여줘야 할 때만 쓴다. */
  public BusinessException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode getErrorCode() {
    return errorCode;
  }
}
