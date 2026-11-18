package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.chat.controller.ChatMessageController;
import com.sahmhoot.chat.dto.SendChatRequest;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.service.ChatService;
import java.security.Principal;
import org.junit.jupiter.api.Test;

class ChatMessageControllerTest {
  private final ChatService service = mock(ChatService.class);
  private final ChatMessageController controller = new ChatMessageController(service);
  private final SendChatRequest request = new SendChatRequest("질문", null);

  @Test void authenticatedUserIdIsPassedToService() {
    controller.send(42L, request, () -> "2");
    verify(service).send(42L, 2L, request);
  }

  @Test void missingOrInvalidPrincipalIsUnauthorized() {
    for (Principal principal : new Principal[] {null, () -> null, () -> "invalid"}) {
      assertThatThrownBy(() -> controller.send(42L, request, principal))
          .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.code()).isEqualTo(ChatErrorCode.UNAUTHORIZED));
    }
    verifyNoInteractions(service);
  }
}
