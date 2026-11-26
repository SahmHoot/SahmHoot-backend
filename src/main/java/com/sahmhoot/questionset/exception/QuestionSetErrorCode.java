package com.sahmhoot.questionset.exception;

public enum QuestionSetErrorCode {
  QUESTION_NOT_FOUND(404, "존재하지 않는 문항입니다."),
  VALIDATION_FAILED(400, "입력값 형식 오류입니다."),
  UNAUTHORIZED(401, "인증이 필요합니다."),
  FORBIDDEN(403, "권한이 없습니다."),
  QUESTION_SET_NOT_FOUND(404, "존재하지 않는 문제 세트입니다."),
  QUESTION_SET_IN_USE(409, "퀴즈에 사용된 문제 세트는 삭제할 수 없습니다."),
  ORDER_MISMATCH(400, "순서 목록이 세트 문항과 다릅니다."),
  QUESTION_IMAGE_NOT_FOUND(404, "문항 이미지를 찾을 수 없습니다."),
  QUESTION_IMAGE_NOT_AVAILABLE(409, "연결할 수 없는 이미지입니다."),
  IMAGE_TOO_LARGE(413, "이미지 업로드 용량 한도를 초과했습니다."),
  UNSUPPORTED_IMAGE_FORMAT(415, "지원하지 않는 이미지 형식입니다."),
  IMAGE_STORAGE_UNAVAILABLE(503, "이미지 저장소를 일시적으로 사용할 수 없습니다."),
  INTERNAL_ERROR(500, "서버 내부 오류가 발생했습니다.");

  public final int status;
  public final String message;
  QuestionSetErrorCode(int status, String message) {
    this.status = status;
    this.message = message;
  }
}
