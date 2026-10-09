package com.sahmhoot.quiz.exception;

import lombok.Getter;

/**
 * 퀴즈 영역의 임시 비즈니스 예외.
 * 팀 공통 예외 계약이 정해지면 교체한다.
 */
@Getter
public class BusinessException extends RuntimeException {

  private final ErrorCode errorCode;

  public BusinessException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.errorCode = errorCode;
  }

  public BusinessException(ErrorCode errorCode, String customMessage) {
    super(customMessage);
    this.errorCode = errorCode;
  }
}
