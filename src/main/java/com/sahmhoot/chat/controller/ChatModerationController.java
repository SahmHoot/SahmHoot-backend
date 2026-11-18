package com.sahmhoot.chat.controller;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.service.ChatModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}/chat")
@RequiredArgsConstructor
public class ChatModerationController {
  private final ChatModerationService moderation;

  @GetMapping("/messages/{messageId}/author")
  public ChatAuthorResponse author(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long messageId) {
    return moderation.author(roomId, me.userId(), messageId);
  }

  @PutMapping("/restrictions/{participantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void restrict(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long participantId) {
    moderation.restrict(roomId, me.userId(), participantId);
  }

  @DeleteMapping("/restrictions/{participantId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void release(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long participantId) {
    moderation.release(roomId, me.userId(), participantId);
  }

  @DeleteMapping("/messages/{messageId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long messageId) {
    moderation.delete(roomId, me.userId(), messageId);
  }
}
