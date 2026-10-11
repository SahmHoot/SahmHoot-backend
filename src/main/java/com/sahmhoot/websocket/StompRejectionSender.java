package com.sahmhoot.websocket;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 인터셉터에서 거부한 프레임을 클라이언트에 알린다.
 *
 * <p>preserveReceiveOrder(06 5절)를 켜면 인터셉터 예외가 순서 보장 래퍼에서 로그만 남고 사라져 클라이언트가 아무것도 받지
 * 못한다. 그래서 예외 대신 여기서 직접 보낸다.
 *
 * <ul>
 *   <li>CONNECT 거부: STOMP ERROR 프레임(message = 코드). Spring이 보낸 뒤 연결을 닫는다
 *   <li>SUBSCRIBE·SEND 거부: 연결은 유지하고 /user/queue/errors로 그 세션에만 ERROR 메시지
 * </ul>
 *
 * 순환 참조(설정 → 인터셉터 → 브로커 빈)를 피하려고 {@code @Lazy}로 받는다.
 */
@Component
public class StompRejectionSender {

  private final MessageChannel clientOutboundChannel;
  private final SimpMessagingTemplate messagingTemplate;

  public StompRejectionSender(
      @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel,
      @Lazy SimpMessagingTemplate messagingTemplate) {
    this.clientOutboundChannel = clientOutboundChannel;
    this.messagingTemplate = messagingTemplate;
  }

  public void rejectConnect(String sessionId, String code) {
    StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
    error.setMessage(code);
    error.setSessionId(sessionId);
    error.setLeaveMutable(true);
    clientOutboundChannel.send(
        MessageBuilder.createMessage(new byte[0], error.getMessageHeaders()));
  }

  public void rejectFrame(String userName, String sessionId, String code, String message) {
    SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    headers.setSessionId(sessionId);
    headers.setLeaveMutable(true);
    messagingTemplate.convertAndSendToUser(
        userName,
        "/queue/errors",
        new StompErrorPayload("ERROR", Instant.now(), code, message),
        headers.getMessageHeaders());
  }

  /** 06 4절 ERROR 형태: type, sentAt, code, message. */
  record StompErrorPayload(String type, Instant sentAt, String code, String message) {}
}
