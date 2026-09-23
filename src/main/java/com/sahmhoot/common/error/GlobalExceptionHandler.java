package com.sahmhoot.common.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

  /** 필수 쿼리 파라미터가 빠진 경우. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ErrorResponse> handleMissingParameter(
      MissingServletRequestParameterException e) {
    return respond(
        ErrorResponse.of(
            ErrorCode.VALIDATION_FAILED,
            List.of(new ErrorResponse.FieldError(e.getParameterName(), "필수 값입니다."))));
  }

  /** 파라미터·경로 변수의 형식이 맞지 않는 경우(예: 숫자 자리에 문자). */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return respond(
        ErrorResponse.of(
            ErrorCode.VALIDATION_FAILED,
            List.of(new ErrorResponse.FieldError(e.getName(), "형식이 올바르지 않습니다."))));
  }

  /** 본문이 없거나 JSON 형식이 잘못된 경우. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e) {
    return respond(ErrorResponse.of(ErrorCode.VALIDATION_FAILED));
  }

  /** 매핑된 컨트롤러가 없는 경로. */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
    return respond(ErrorResponse.of(ErrorCode.NOT_FOUND));
  }

  /** 경로는 있지만 HTTP 메서드가 다른 경우. 지원하는 메서드는 Allow 헤더로 알려준다. */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ErrorResponse> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException e) {
    ErrorResponse body = ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED);
    HttpHeaders headers = new HttpHeaders();
    if (e.getSupportedHttpMethods() != null) {
      headers.setAllow(e.getSupportedHttpMethods());
    }
    return ResponseEntity.status(body.status()).headers(headers).body(body);
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
