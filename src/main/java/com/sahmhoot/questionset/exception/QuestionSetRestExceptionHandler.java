package com.sahmhoot.questionset.exception;

import com.sahmhoot.questionset.controller.QuestionSetController;
import com.sahmhoot.questionset.controller.QuestionController;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.method.ParameterErrors;

@Slf4j
@RestControllerAdvice(assignableTypes = {QuestionSetController.class, QuestionController.class})
public class QuestionSetRestExceptionHandler {
  @ExceptionHandler(QuestionSetException.class)
  public ResponseEntity<QuestionSetErrorResponse> business(QuestionSetException error) {
    return response(error.getCode(), null);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<QuestionSetErrorResponse> validation(MethodArgumentNotValidException error) {
    return response(QuestionSetErrorCode.VALIDATION_FAILED, error.getBindingResult().getFieldErrors()
        .stream().map(e -> new QuestionSetErrorResponse.FieldError(inputField(e.getField()), e.getDefaultMessage())).toList());
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  public ResponseEntity<QuestionSetErrorResponse> parameters(HandlerMethodValidationException error) {
    return response(QuestionSetErrorCode.VALIDATION_FAILED, error.getParameterValidationResults()
        .stream().flatMap(result -> {
          if (result instanceof ParameterErrors fields) {
            return fields.getFieldErrors().stream().map(e ->
                new QuestionSetErrorResponse.FieldError(inputField(e.getField()), e.getDefaultMessage()));
          }
          return result.getResolvableErrors().stream().map(e ->
              new QuestionSetErrorResponse.FieldError(result.getMethodParameter().getParameterName(),
                  e.getDefaultMessage()));
        }).toList());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<QuestionSetErrorResponse> malformed(Exception error) {
    String field = error instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : "body";
    return response(QuestionSetErrorCode.VALIDATION_FAILED,
        List.of(new QuestionSetErrorResponse.FieldError(field, "입력값 형식을 확인해주세요.")));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<QuestionSetErrorResponse> unexpected(Exception error) {
    log.error("QuestionSet request failed", error);
    return response(QuestionSetErrorCode.INTERNAL_ERROR, null);
  }

  private ResponseEntity<QuestionSetErrorResponse> response(QuestionSetErrorCode code,
      List<QuestionSetErrorResponse.FieldError> fields) {
    return ResponseEntity.status(code.status)
        .body(new QuestionSetErrorResponse(code.status, code.name(), code.message, fields));
  }

  private String inputField(String property) {
    return switch (property) {
      case "titleValueValid" -> "title";
      case "visibilityValueValid" -> "visibility";
      default -> property;
    };
  }
}
