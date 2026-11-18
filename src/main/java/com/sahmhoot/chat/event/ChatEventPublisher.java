package com.sahmhoot.chat.event;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ChatEventPublisher {
  private final SimpMessagingTemplate messaging;

  @TransactionalEventListener
  public void publish(ChatMessageEvent event) {
    messaging.convertAndSend("/topic/rooms/" + event.roomId() + "/chat", event.payload());
  }
}
