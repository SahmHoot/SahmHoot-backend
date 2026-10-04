package com.sahmhoot.websocket;

import com.sahmhoot.common.error.ApiException;
import com.sahmhoot.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * @MessageMapping 핸들러(answer·chat·reaction)에서 던진 ApiException을 ERROR 메시지로 바꾼다.
 * broadcast = false라서 요청을 보낸 WebSocket 세션에만 간다(06 3절).
 */
@Slf4j
@ControllerAdvice
public class StompExceptionAdvice {

  @MessageExceptionHandler(ApiException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public StompErrorMessage handleApi(ApiException e) {
    return StompErrorMessage.of(e.getErrorCode().name(), e.getMessage());
  }

  @MessageExceptionHandler(Exception.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public StompErrorMessage handleUnknown(Exception e) {
    log.error("STOMP 처리 중 예외", e);
    return StompErrorMessage.of(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage());
  }
}
