package com.sahmhoot.room.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomErrorResponse(int status, String code, String message,
    List<FieldError> fieldErrors) {
  public record FieldError(String field, String message) {}

  public static RoomErrorResponse of(RoomErrorCode code, String message) {
    return new RoomErrorResponse(code.status().value(), code.name(), message, null);
  }
}
