package com.sahmhoot.quiz.exception;

import com.sahmhoot.quiz.controller.QuizAnswerMessageController;
import com.sahmhoot.quiz.dto.message.ErrorMessage;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * 퀴즈 답안 STOMP 핸들러에만 적용하는 임시 예외 처리기.
 * 06 실시간 규격 2절 및 3절:
 * - STOMP 처리 중 발생한 예외는 /user/queue/errors 채널로 요청을 보낸 세션에만 전송한다.
 * - 페이로드 형태: { "type": "ERROR", "sentAt": ..., "code": ..., "message": ... }
 */
@Slf4j
@ControllerAdvice(assignableTypes = QuizAnswerMessageController.class)
public class QuizStompExceptionHandler {

  @MessageExceptionHandler(BusinessException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorMessage handleBusinessException(BusinessException e) {
    ErrorCode errorCode = e.getErrorCode();
    log.warn("STOMP BusinessException occurred: [{}] {}", errorCode.name(), e.getMessage());
    return ErrorMessage.of(errorCode.name(), e.getMessage(), Instant.now());
  }

  @MessageExceptionHandler({
      org.springframework.web.bind.MethodArgumentNotValidException.class,
      org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException.class,
      jakarta.validation.ValidationException.class,
      org.springframework.messaging.converter.MessageConversionException.class,
      org.springframework.messaging.handler.annotation.support.MethodArgumentTypeMismatchException.class,
      IllegalArgumentException.class
  })
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorMessage handleValidationException(Exception e) {
    log.warn("STOMP validation/conversion failed: {}", e.getMessage());
    return ErrorMessage.of(ErrorCode.VALIDATION_FAILED.name(), "입력값 형식 오류입니다.", Instant.now());
  }

  @MessageExceptionHandler(Exception.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public ErrorMessage handleException(Exception e) {
    log.error("Unhandled STOMP exception occurred", e);
    return ErrorMessage.of(ErrorCode.INTERNAL_ERROR.name(), "서버 내부 오류가 발생했습니다.", Instant.now());
  }
}
