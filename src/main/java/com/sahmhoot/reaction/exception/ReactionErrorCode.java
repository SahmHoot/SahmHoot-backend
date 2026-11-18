package com.sahmhoot.reaction.exception;

/** 반응 전송 오류 코드. 다른 모듈과 겹치는 이름도 모듈 규칙에 따라 여기 다시 선언한다. */
public enum ReactionErrorCode {
  UNAUTHORIZED("로그인이 필요합니다."),
  VALIDATION_FAILED("보낼 수 없는 반응입니다."),
  FORBIDDEN("방 소유자는 반응을 보낼 수 없습니다."),
  NOT_PARTICIPANT("이 수업의 참여자가 아닙니다."),
  ROOM_NOT_FOUND("수업을 찾을 수 없습니다."),
  ROOM_CLOSED("종료된 수업입니다."),
  INTERNAL_ERROR("서버 내부 오류가 발생했습니다.");

  private final String message;

  ReactionErrorCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
