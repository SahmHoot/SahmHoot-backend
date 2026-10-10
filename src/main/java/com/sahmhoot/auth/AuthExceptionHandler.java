package com.sahmhoot.auth;

import com.sahmhoot.common.error.ApiException;
import com.sahmhoot.common.error.ErrorCode;
import com.sahmhoot.common.error.ErrorResponse;
import com.sahmhoot.user.UserController;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 인증·사용자 API(auth, user 패키지) 전용 에러 핸들러.
 *
 * <p>다른 모듈의 핸들러와 범위가 겹치지 않게 패키지를 한정한다. 범위가 겹치면 먼저 검사된 Advice가 예외를 가져가서
 * 다른 모듈의 핸들러가 동작하지 않는다. 어느 핸들러도 처리하지 못한 에러는 공통 에러 처리에 맡긴다.
 */
@Slf4j
@RestControllerAdvice(basePackageClasses = {AuthController.class, UserController.class})
public class AuthExceptionHandler {

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
