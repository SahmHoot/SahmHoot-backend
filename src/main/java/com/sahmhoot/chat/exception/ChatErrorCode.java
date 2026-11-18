package com.sahmhoot.chat.exception;

/** 05·06 명세의 채팅 전송 오류. 빈도 제한 오류는 기존 STOMP 인터셉터가 처리한다. */
public enum ChatErrorCode {
  UNAUTHORIZED("인증이 필요합니다."),
  ROOM_NOT_FOUND("존재하지 않는 수업 방입니다."),
  ROOM_CLOSED("이미 종료된 수업 방입니다."),
  NOT_PARTICIPANT("해당 방의 참여자가 아닙니다."),
  CHAT_BLOCKED("채팅이 제한된 참여자입니다."),
  FORBIDDEN("권한이 없습니다."),
  CANNOT_BLOCK_OWNER("수업방 소유자는 채팅을 제한할 수 없습니다."),
  VALIDATION_FAILED("입력값 형식 오류입니다."),
  INTERNAL_ERROR("서버 내부 오류가 발생했습니다.");

  private final String message;

  ChatErrorCode(String message) { this.message = message; }
  public String message() { return message; }
}
