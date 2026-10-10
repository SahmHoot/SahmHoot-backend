package com.sahmhoot.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** { "status": 409, "code": "...", "message": "...", "fieldErrors": [...] } */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String code, String message, List<FieldError> fieldErrors) {

  public record FieldError(String field, String message) {}

  public static ErrorResponse of(ErrorCode code, String message) {
    return new ErrorResponse(code.status().value(), code.name(), message, null);
  }

  /** 필터처럼 Jackson을 거치지 않는 곳에서 쓰는 JSON 문자열. */
  public String toJson() {
    return "{\"status\":" + status + ",\"code\":\"" + code + "\",\"message\":\""
        + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
  }
}
