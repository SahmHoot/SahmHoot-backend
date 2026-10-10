package com.sahmhoot.quiz.exception;

import org.springframework.http.HttpStatus;

/**
 * 퀴즈 흐름에서 현재 사용하는 05/06 명세 에러 코드.
 * 팀 공통 ErrorCode가 합의되면 그 계약으로 교체한다.
 */
public enum ErrorCode {
  // 공통 / 시스템
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값 형식 오류입니다."),
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),

  // 수업 방 / 참가자
  ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 수업 방입니다."),
  ROOM_CLOSED(HttpStatus.CONFLICT, "이미 종료된 수업 방입니다."),
  NOT_PARTICIPANT(HttpStatus.FORBIDDEN, "해당 방의 참여자가 아닙니다."),

  // 문제 세트
  QUESTION_SET_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 문제 세트입니다."),
  EMPTY_QUESTION_SET(HttpStatus.BAD_REQUEST, "문항이 없는 문제 세트로 퀴즈를 시작할 수 없습니다."),
  QUESTION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 문항입니다."),

  // 1. Quiz Core
  QUIZ_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 진행 중인 퀴즈가 있습니다."),
  QUIZ_RUN_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 퀴즈 회차입니다."),
  QUIZ_NOT_RUNNING(HttpStatus.CONFLICT, "진행 중인 퀴즈가 아닙니다."),
  ROOM_NOT_SHOWING_RESULT(HttpStatus.CONFLICT, "결과 화면 상태가 아닙니다."),

  // 5. Answer / Realtime
  QUESTION_CLOSED(HttpStatus.BAD_REQUEST, "마감된 문항입니다.");

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
