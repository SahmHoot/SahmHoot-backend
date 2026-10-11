package com.sahmhoot.websocket;

/**
 * STOMP 인터셉터가 프레임을 거부할 때 클라이언트에 보내는 코드. 이름은 05·06 명세의 에러 코드를 따른다.
 *
 * <p>websocket은 모든 모듈이 같이 쓰는 연결 계층이라 특정 모듈의 ErrorCode에 묶지 않고 여기 따로 둔다.
 */
public enum StompRejectCode {
  UNAUTHORIZED("로그인이 필요합니다."),
  FORBIDDEN("권한이 없습니다."),
  NOT_PARTICIPANT("이 수업의 참여자가 아닙니다."),
  RATE_LIMITED("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");

  private final String message;

  StompRejectCode(String message) {
    this.message = message;
  }

  public String message() {
    return message;
  }
}
