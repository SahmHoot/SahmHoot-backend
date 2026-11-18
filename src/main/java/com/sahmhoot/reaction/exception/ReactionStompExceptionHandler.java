package com.sahmhoot.reaction.exception;

import com.sahmhoot.reaction.controller.ReactionMessageController;
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

/**
 * 반응 STOMP 핸들러에만 적용한다. 오류는 요청을 보낸 세션의 /user/queue/errors로만 보낸다.
 *
 * <p>STOMP의 {@code @MessageExceptionHandler}는 {@code @ControllerAdvice}로 등록한다(퀴즈 답안 처리기와 같음).
 */
@Slf4j
@ControllerAdvice(assignableTypes = ReactionMessageController.class)
@RequiredArgsConstructor
public class ReactionStompExceptionHandler {
  private final Clock clock;

  @MessageExceptionHandler(ReactionException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ReactionErrorResponse business(ReactionException exception) {
    return ReactionErrorResponse.of(exception.code(), Instant.now(clock));
  }

  @MessageExceptionHandler({MethodArgumentNotValidException.class, ValidationException.class,
      MessageConversionException.class, MethodArgumentTypeMismatchException.class,
      IllegalArgumentException.class})
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ReactionErrorResponse validation(Exception exception) {
    return ReactionErrorResponse.of(ReactionErrorCode.VALIDATION_FAILED, Instant.now(clock));
  }

  @MessageExceptionHandler(Exception.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ReactionErrorResponse unexpected(Exception exception) {
    log.error("Reaction handling failed", exception);
    return ReactionErrorResponse.of(ReactionErrorCode.INTERNAL_ERROR, Instant.now(clock));
  }
}
