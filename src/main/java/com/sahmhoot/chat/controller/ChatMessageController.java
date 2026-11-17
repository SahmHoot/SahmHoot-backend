package com.sahmhoot.chat.controller;

import com.sahmhoot.chat.dto.SendChatRequest;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.service.ChatService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class ChatMessageController {
  private final ChatService service;

  @MessageMapping("/rooms/{roomId}/chat")
  public void send(@DestinationVariable("roomId") Long roomId,
      @Valid @Payload SendChatRequest request, Principal principal) {
    if (principal == null || principal.getName() == null) throw new ChatException(ChatErrorCode.UNAUTHORIZED);
    Long userId;
    try {
      userId = Long.valueOf(principal.getName());
    } catch (NumberFormatException e) {
      throw new ChatException(ChatErrorCode.UNAUTHORIZED);
    }
    service.send(roomId, userId, request);
  }
}
