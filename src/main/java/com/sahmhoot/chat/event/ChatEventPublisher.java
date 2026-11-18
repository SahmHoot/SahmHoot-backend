package com.sahmhoot.chat.event;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ChatEventPublisher {
  private final SimpMessagingTemplate messaging;
  private final SimpUserRegistry users;

  @TransactionalEventListener
  public void publish(ChatMessageEvent event) {
    messaging.convertAndSend("/topic/rooms/" + event.roomId() + "/chat", event.payload());
  }

  @TransactionalEventListener
  public void publish(ChatStatusChangedEvent event) {
    var user = users.getUser(event.userId().toString());
    if (user == null) return;

    String destination = "/queue/rooms/" + event.roomId() + "/me";
    // 순서 보장 브로커에서 사용자 단위 전송은 여러 세션에 같은 헤더를 재사용한다.
    // 세션마다 새 헤더를 쓰고, 세션 ID를 목적지에도 지정해 연결 종료 시 다른 탭으로 재전송되지 않게 한다.
    for (var session : user.getSessions()) {
      var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
      headers.setSessionId(session.getId());
      headers.setLeaveMutable(true);
      messaging.convertAndSendToUser(session.getId(), destination, event.payload(), headers.getMessageHeaders());
    }
  }

  @TransactionalEventListener
  public void publish(ChatMessageDeletedEvent event) {
    messaging.convertAndSend("/topic/rooms/" + event.roomId() + "/chat", event.payload());
  }
}
