package com.sahmhoot.chat.exception;

import com.sahmhoot.chat.controller.ChatMessageController;
import jakarta.validation.ValidationException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.handler.annotation.support.MethodArgumentTypeMismatchException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

@Slf4j
@ControllerAdvice(assignableTypes = ChatMessageController.class)
@RequiredArgsConstructor
public class ChatStompExceptionHandler {
  private final Clock clock;

  @MessageExceptionHandler(ChatException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ChatErrorResponse business(ChatException exception) {
    return ChatErrorResponse.of(exception.code(), Instant.now(clock));
  }

  @MessageExceptionHandler({MethodArgumentNotValidException.class, ValidationException.class,
      MessageConversionException.class, MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ChatErrorResponse validation(Exception exception) {
    return ChatErrorResponse.of(ChatErrorCode.VALIDATION_FAILED, Instant.now(clock));
  }

  @MessageExceptionHandler(Exception.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ChatErrorResponse unexpected(Exception exception) {
    // 메시지 본문이나 변환 실패 원문에는 사용자가 보낸 내용이 포함될 수 있다.
    log.error("Chat handling failed: {}", exception.getClass().getSimpleName());
    return ChatErrorResponse.of(ChatErrorCode.INTERNAL_ERROR, Instant.now(clock));
  }
}
