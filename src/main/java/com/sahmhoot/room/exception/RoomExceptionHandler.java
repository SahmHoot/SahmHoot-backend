package com.sahmhoot.room.exception;

import com.sahmhoot.room.controller.RoomController;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice(assignableTypes = RoomController.class)
public class RoomExceptionHandler {
  @ExceptionHandler(RoomException.class)
  public ResponseEntity<RoomErrorResponse> business(RoomException error) {
    return ResponseEntity.status(error.getErrorCode().status())
        .body(RoomErrorResponse.of(error.getErrorCode(), error.getMessage()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<RoomErrorResponse> path(MethodArgumentTypeMismatchException error) {
    RoomErrorCode code = RoomErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status()).body(new RoomErrorResponse(code.status().value(),
        code.name(), code.defaultMessage(), List.of(new RoomErrorResponse.FieldError(
            error.getName(), "숫자 형식의 교실 번호를 입력해 주세요."))));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<RoomErrorResponse> validation(MethodArgumentNotValidException error) {
    var fields = error.getBindingResult().getFieldErrors().stream()
        .map(e -> new RoomErrorResponse.FieldError(e.getField(), e.getDefaultMessage())).toList();
    RoomErrorCode code = RoomErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status())
        .body(new RoomErrorResponse(code.status().value(), code.name(), code.defaultMessage(), fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<RoomErrorResponse> malformed(HttpMessageNotReadableException error) {
    RoomErrorCode code = RoomErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status()).body(new RoomErrorResponse(code.status().value(), code.name(),
        "요청 본문을 읽을 수 없습니다.", List.of(new RoomErrorResponse.FieldError("body", "JSON 형식을 확인해 주세요."))));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<RoomErrorResponse> unexpected(Exception error) throws Exception {
    if (error instanceof org.springframework.web.ErrorResponse) {
      throw error;
    }
    log.error("Room request failed", error);
    return ResponseEntity.internalServerError()
        .body(RoomErrorResponse.of(RoomErrorCode.INTERNAL_ERROR, RoomErrorCode.INTERNAL_ERROR.defaultMessage()));
  }
}
