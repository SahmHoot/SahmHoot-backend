package com.sahmhoot.chat.controller;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.dto.ChatRestrictionResponse;
import com.sahmhoot.chat.dto.UpdateChatRestrictionRequest;
import com.sahmhoot.chat.service.ChatModerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}")
@RequiredArgsConstructor
public class ChatModerationController {
  private final ChatModerationService moderation;

  @GetMapping("/messages/{messageId}/author")
  public ChatAuthorResponse author(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long messageId) {
    return moderation.author(roomId, me.userId(), messageId);
  }

  @PatchMapping("/participants/{participantId}")
  public ChatRestrictionResponse updateRestriction(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long participantId, @Valid @RequestBody UpdateChatRestrictionRequest request) {
    return moderation.updateRestriction(roomId, me.userId(), participantId, request.chatBlocked());
  }

  @DeleteMapping("/messages/{messageId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long roomId,
      @PathVariable Long messageId) {
    moderation.delete(roomId, me.userId(), messageId);
  }
}
