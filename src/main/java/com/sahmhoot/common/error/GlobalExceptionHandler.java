package com.sahmhoot.common.error;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
    ErrorCode code = e.getErrorCode();
    return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldError> fields =
        e.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
            .toList();
    ErrorCode code = ErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status())
        .body(new ErrorResponse(code.status().value(), code.name(), code.defaultMessage(), fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
    ErrorCode code = ErrorCode.VALIDATION_FAILED;
    return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, "요청 본문을 읽을 수 없습니다."));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
    log.error("처리하지 못한 예외", e);
    ErrorCode code = ErrorCode.INTERNAL_ERROR;
    return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, code.defaultMessage()));
  }
}
