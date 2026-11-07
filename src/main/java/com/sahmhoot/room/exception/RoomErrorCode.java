package com.sahmhoot.room.exception;

import org.springframework.http.HttpStatus;

public enum RoomErrorCode {
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 수업 방입니다."),
  ROOM_CLOSED(HttpStatus.CONFLICT, "이미 종료된 수업 방입니다."),
  NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "해당 방의 참여자가 아닙니다."),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "입장 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
  NICKNAME_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "사용할 수 없는 닉네임입니다."),
  NICKNAME_ALREADY_USED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

  private final HttpStatus status;
  private final String defaultMessage;

  RoomErrorCode(HttpStatus status, String defaultMessage) {
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
