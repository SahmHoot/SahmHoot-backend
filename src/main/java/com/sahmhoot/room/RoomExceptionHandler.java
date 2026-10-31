package com.sahmhoot.room;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.auth.exception.ErrorResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestControllerAdvice(assignableTypes = RoomController.class)
public class RoomExceptionHandler {
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> business(ApiException error) {
    return ResponseEntity.status(error.getErrorCode().status())
        .body(ErrorResponse.of(error.getErrorCode(), error.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException error) {
    var fields = error.getBindingResult().getFieldErrors().stream()
        .map(e -> new ErrorResponse.FieldError(e.getField(), e.getDefaultMessage())).toList();
    ErrorCode code = ErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status())
        .body(new ErrorResponse(code.status().value(), code.name(), code.defaultMessage(), fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> malformed(HttpMessageNotReadableException error) {
    ErrorCode code = ErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status()).body(new ErrorResponse(code.status().value(), code.name(),
        "요청 본문을 읽을 수 없습니다.", List.of(new ErrorResponse.FieldError("body", "JSON 형식을 확인해 주세요."))));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> unexpected(Exception error) {
    log.error("Room request failed", error);
    return ResponseEntity.internalServerError()
        .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
  }
}
