package com.sahmhoot.common.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드. 05 API 명세 v1.3의 코드 전부와 공통 NOT_FOUND·METHOD_NOT_ALLOWED. 응답의 {@code code}는 enum 이름 그대로 나간다.
 *
 * <p>모든 역할이 공유하는 계약이라 뼈대에 둔다. 명세에 코드를 추가·변경할 때는 해당 도메인 묶음에 넣는다.
 */
public enum ErrorCode {
  // 공통
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 주소를 찾을 수 없습니다."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

  // 인증 (3)
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
  EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
  EMAIL_DOMAIN_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "허용되지 않은 이메일 도메인입니다."),
  VERIFICATION_TOO_FREQUENT(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 요청해 주세요."),
  MAIL_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "메일 발송에 실패했습니다."),
  VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "인증 요청을 찾을 수 없습니다. 코드를 다시 받아 주세요."),
  VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다."),
  VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "인증 코드가 만료되었습니다."),
  EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),

  // 문제 세트 (4)
  QUESTION_SET_NOT_FOUND(HttpStatus.NOT_FOUND, "문제 세트를 찾을 수 없습니다."),
  QUESTION_SET_IN_USE(HttpStatus.CONFLICT, "진행 중인 수업에서 사용한 세트입니다."),
  QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "문항을 찾을 수 없습니다."),
  ORDER_MISMATCH(HttpStatus.BAD_REQUEST, "문항 순서 목록이 올바르지 않습니다."),

  // 수업 방 (2)
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "수업을 찾을 수 없습니다."),
  NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "이 수업의 참여자가 아닙니다."),
  NICKNAME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "사용할 수 없는 닉네임입니다."),
  PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "참여자를 찾을 수 없습니다."),
  CANNOT_BLOCK_OWNER(HttpStatus.BAD_REQUEST, "수업 소유자는 제한할 수 없습니다."),

  // 퀴즈 (1)
  EMPTY_QUESTION_SET(HttpStatus.BAD_REQUEST, "문항이 없는 세트입니다."),
  QUIZ_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 퀴즈가 있습니다."),
  QUIZ_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "퀴즈를 찾을 수 없습니다."),
  QUIZ_NOT_RUNNING(HttpStatus.CONFLICT, "진행 중인 퀴즈가 아닙니다."),
  ROOM_NOT_SHOWING_RESULT(HttpStatus.CONFLICT, "결과 화면이 아닙니다."),
  /** STOMP ERROR 메시지용. */
  QUESTION_CLOSED(HttpStatus.CONFLICT, "마감된 문항입니다."),

  // 채팅 (5)
  MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다."),
  MESSAGE_DELETED(HttpStatus.BAD_REQUEST, "삭제된 메시지입니다."),
  /** STOMP ERROR 메시지용. */
  CHAT_BLOCKED(HttpStatus.FORBIDDEN, "채팅이 제한되었습니다.");

  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }
}
