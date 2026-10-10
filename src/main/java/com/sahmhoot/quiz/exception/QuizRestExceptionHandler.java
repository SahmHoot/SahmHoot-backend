package com.sahmhoot.quiz.exception;

import com.sahmhoot.quiz.controller.QuizRunController;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 퀴즈 REST 컨트롤러에만 적용하는 임시 예외 처리기.
 * 팀 공통 예외 정책이 정해지면 공통 처리기로 교체한다.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = QuizRunController.class)
public class QuizRestExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("BusinessException occurred: [{} - {}] {}", errorCode.getStatus(), errorCode.name(), e.getMessage());
    return ResponseEntity.status(errorCode.getStatus())
        .body(ErrorResponse.of(errorCode, e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
    List<ErrorResponse.FieldErrorDetail> fieldErrors = e.getBindingResult().getFieldErrors().stream()
        .map(error -> new ErrorResponse.FieldErrorDetail(error.getField(), error.getDefaultMessage()))
        .toList();

    log.warn("Validation failed: {}", fieldErrors);
    return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getStatus())
        .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, fieldErrors));
  }

}
