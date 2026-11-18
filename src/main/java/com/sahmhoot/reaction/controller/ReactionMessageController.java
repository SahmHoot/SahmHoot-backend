package com.sahmhoot.reaction.controller;

import com.sahmhoot.reaction.dto.SendReactionRequest;
import com.sahmhoot.reaction.exception.ReactionErrorCode;
import com.sahmhoot.reaction.exception.ReactionException;
import com.sahmhoot.reaction.service.ReactionService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * 이모지 반응 전송(RE-01). 1초 3건 제한(RE-04)은 이 핸들러 앞의 StompAuthChannelInterceptor가 처리한다.
 * 성공하면 따로 응답하지 않고, 보낸 사람도 reaction 채널로 돌아온 REACTION을 받는다.
 */
@Controller
@RequiredArgsConstructor
public class ReactionMessageController {
  private final ReactionService service;

  @MessageMapping("/rooms/{roomId}/reaction")
  public void send(@DestinationVariable("roomId") Long roomId,
      @Payload SendReactionRequest request, Principal principal) {
    if (principal == null || principal.getName() == null) {
      throw new ReactionException(ReactionErrorCode.UNAUTHORIZED);
    }
    Long userId;
    try {
      userId = Long.valueOf(principal.getName());
    } catch (NumberFormatException e) {
      throw new ReactionException(ReactionErrorCode.UNAUTHORIZED);
    }
    service.send(roomId, userId, request);
  }
}
