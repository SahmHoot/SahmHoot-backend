package com.sahmhoot.chat.controller;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.chat.dto.ChatMessageResponse;
import com.sahmhoot.chat.service.ChatModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 05 #28·#29 공지 고정·해제. 한 방에 여러 메시지를 고정할 수 있어 대상 메시지를 경로로 받는다. */
@RestController
@RequestMapping("/api/rooms/{roomId}/pinned-messages/{messageId}")
@RequiredArgsConstructor
public class ChatPinController {
  private final ChatModerationService moderation;

  @PutMapping
  public ChatMessageResponse pin(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long messageId) {
    return moderation.pin(roomId, me.userId(), messageId);
  }

  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unpin(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long messageId) {
    moderation.unpin(roomId, me.userId(), messageId);
  }
}
