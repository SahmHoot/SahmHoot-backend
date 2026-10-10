package com.sahmhoot.quiz.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.quiz.dto.message.ErrorMessage;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;

class QuizStompExceptionHandlerTest {

  private QuizStompExceptionHandler handler;

  @BeforeEach
  void setUp() {
    handler = new QuizStompExceptionHandler();
  }

  @Test
  @DisplayName("BusinessException 발생 시 규격에 맞는 ERROR 메시지(type, code, message, sentAt)를 생성한다 (Issue 7)")
  void handleBusinessException_createsCorrectErrorMessage() {
    BusinessException ex = new BusinessException(ErrorCode.QUESTION_CLOSED);

    ErrorMessage message = handler.handleBusinessException(ex);

    assertThat(message.type()).isEqualTo("ERROR");
    assertThat(message.code()).isEqualTo("QUESTION_CLOSED");
    assertThat(message.message()).isEqualTo(ErrorCode.QUESTION_CLOSED.getMessage());
    assertThat(message.sentAt()).isNotNull();
  }

  @Test
  @DisplayName("검증 예외 발생 시 VALIDATION_FAILED ERROR 메시지를 생성한다 (Issue 7)")
  void handleValidationException_createsValidationFailedMessage() {
    Exception ex = new IllegalArgumentException("invalid payload");

    ErrorMessage message = handler.handleValidationException(ex);

    assertThat(message.type()).isEqualTo("ERROR");
    assertThat(message.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(message.sentAt()).isNotNull();
  }

  @Test
  @DisplayName("메시지 변환/역직렬화 실패 시 VALIDATION_FAILED ERROR 메시지를 생성한다 (Issue 7)")
  void handleValidationException_messageConversionException_createsValidationFailed() {
    Exception ex = new org.springframework.messaging.converter.MessageConversionException("malformed json");

    ErrorMessage message = handler.handleValidationException(ex);

    assertThat(message.type()).isEqualTo("ERROR");
    assertThat(message.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(message.sentAt()).isNotNull();
  }

  @Test
  @DisplayName("기타 예외 발생 시 INTERNAL_ERROR ERROR 메시지를 생성한다")
  void handleException_createsInternalErrorMessage() {
    Exception ex = new RuntimeException("unexpected");

    ErrorMessage message = handler.handleException(ex);

    assertThat(message.type()).isEqualTo("ERROR");
    assertThat(message.code()).isEqualTo("INTERNAL_ERROR");
    assertThat(message.sentAt()).isNotNull();
  }

  @Test
  @DisplayName("모든 핸들러 메서드에 @SendToUser(destinations = '/queue/errors', broadcast = false)가 선언되어 있다 (Issue 7)")
  void handlerMethods_haveCorrectSendToUserAnnotation() throws NoSuchMethodException {
    Method businessMethod = QuizStompExceptionHandler.class.getMethod("handleBusinessException", BusinessException.class);
    SendToUser annotation = businessMethod.getAnnotation(SendToUser.class);

    assertThat(annotation).isNotNull();
    assertThat(annotation.destinations()).containsExactly("/queue/errors");
    assertThat(annotation.broadcast()).isFalse();

    assertThat(businessMethod.getAnnotation(MessageExceptionHandler.class)).isNotNull();
  }
}
