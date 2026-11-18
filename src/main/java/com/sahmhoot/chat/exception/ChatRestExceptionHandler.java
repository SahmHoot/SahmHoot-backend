package com.sahmhoot.chat.exception;

import com.sahmhoot.chat.controller.ChatModerationController;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice(assignableTypes = ChatModerationController.class)
public class ChatRestExceptionHandler {
  @ExceptionHandler(ChatException.class)
  public ResponseEntity<ChatRestErrorResponse> business(ChatException error) {
    var status = switch (error.code()) {
      case FORBIDDEN, NOT_PARTICIPANT, CHAT_BLOCKED -> HttpStatus.FORBIDDEN;
      case CANNOT_BLOCK_OWNER, VALIDATION_FAILED -> HttpStatus.BAD_REQUEST;
      case ROOM_NOT_FOUND, MESSAGE_NOT_FOUND, PARTICIPANT_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case ROOM_CLOSED -> HttpStatus.CONFLICT;
      case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
      case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
    };
    return response(status, error.code(), List.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ChatRestErrorResponse> path(MethodArgumentTypeMismatchException error) {
    return response(HttpStatus.BAD_REQUEST, ChatErrorCode.VALIDATION_FAILED,
        List.of(new ChatRestErrorResponse.FieldError(error.getName(), "숫자 형식의 값을 입력해 주세요.")));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ChatRestErrorResponse> validation(MethodArgumentNotValidException error) {
    var fields = error.getBindingResult().getFieldErrors().stream()
        .map(e -> new ChatRestErrorResponse.FieldError(e.getField(), e.getDefaultMessage())).toList();
    return ResponseEntity.badRequest().body(new ChatRestErrorResponse(HttpStatus.BAD_REQUEST.value(),
        ChatErrorCode.VALIDATION_FAILED.name(), "입력값이 올바르지 않습니다.", fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ChatRestErrorResponse> malformed(HttpMessageNotReadableException error) {
    return ResponseEntity.badRequest().body(new ChatRestErrorResponse(HttpStatus.BAD_REQUEST.value(),
        ChatErrorCode.VALIDATION_FAILED.name(), "요청 본문을 읽을 수 없습니다.",
        List.of(new ChatRestErrorResponse.FieldError("body", "JSON 형식을 확인해 주세요."))));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ChatRestErrorResponse> unexpected(Exception error) throws Exception {
    if (error instanceof org.springframework.web.ErrorResponse) {
      throw error;
    }
    log.error("Chat moderation failed: {}", error.getClass().getSimpleName());
    return response(HttpStatus.INTERNAL_SERVER_ERROR, ChatErrorCode.INTERNAL_ERROR, List.of());
  }

  private ResponseEntity<ChatRestErrorResponse> response(HttpStatus status, ChatErrorCode code,
      List<ChatRestErrorResponse.FieldError> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ChatRestErrorResponse(status.value(), code.name(), code.message(), fieldErrors));
  }
}
