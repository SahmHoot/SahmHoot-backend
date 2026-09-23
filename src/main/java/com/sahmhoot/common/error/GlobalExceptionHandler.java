package com.sahmhoot.common.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** 컨트롤러에서 나온 예외를 {@link ErrorResponse} 형식으로 바꾼다. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
    return respond(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldError> fieldErrors =
        e.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
            .toList();
    return respond(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, fieldErrors));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
    List<ErrorResponse.FieldError> fieldErrors =
        e.getConstraintViolations().stream()
            .map(v -> new ErrorResponse.FieldError(lastNodeName(v), v.getMessage()))
            .toList();
    return respond(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, fieldErrors));
  }

  /** {@code @RequestParam}·{@code @PathVariable} 검증 실패(Spring 내장 메서드 검증). */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException e) {
    List<ErrorResponse.FieldError> fieldErrors =
        e.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                new ErrorResponse.FieldError(
                                    result.getMethodParameter().getParameterName(),
                                    error.getDefaultMessage())))
            .toList();
    return respond(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, fieldErrors));
  }

  /** 본문이 없거나 JSON 형식이 잘못된 경우. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
    return respond(ErrorResponse.of(ErrorCode.VALIDATION_FAILED));
  }

  /** 컨트롤러 안에서 난 인증 예외. 필터 단계의 401은 SecurityConfig가 처리한다. */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException e) {
    return respond(ErrorResponse.of(ErrorCode.UNAUTHORIZED));
  }

  /** 컨트롤러 안에서 난 권한 예외. 필터 단계의 403은 SecurityConfig가 처리한다. */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
    return respond(ErrorResponse.of(ErrorCode.FORBIDDEN));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
    log.error("처리하지 못한 예외", e);
    return respond(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
  }

  private static ResponseEntity<ErrorResponse> respond(ErrorResponse body) {
    return ResponseEntity.status(body.status()).body(body);
  }

  private static String lastNodeName(ConstraintViolation<?> violation) {
    String name = null;
    for (Path.Node node : violation.getPropertyPath()) {
      name = node.getName();
    }
    return name;
  }
}
