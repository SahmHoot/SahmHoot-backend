package com.sahmhoot.common.error;

import org.springframework.http.HttpStatus;

/** 05 API 명세 5절의 에러 코드. 담당 영역의 코드는 필요할 때 여기에 추가한다. */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
  NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "이 수업의 참여자가 아닙니다."),
  EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
  EMAIL_DOMAIN_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "허용되지 않은 이메일 도메인입니다."),
  VERIFICATION_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "인증 코드는 60초에 한 번 보낼 수 있습니다."),
  MAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요."),
  VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "인증 요청이 없습니다. 인증 코드를 다시 받아 주세요."),
  VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
  VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다."),
  EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해 주세요."),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "수업을 찾을 수 없습니다."),
  ROOM_CLOSED(HttpStatus.CONFLICT, "종료된 수업입니다."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}
