package com.sahmhoot.chat;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.chat.controller.ChatPinController;
import com.sahmhoot.chat.dto.ChatMessageResponse;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.exception.ChatRestExceptionHandler;
import com.sahmhoot.chat.service.ChatModerationService;
import com.sahmhoot.user.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatPinControllerTest {
  private static final String PIN = "/api/rooms/42/pinned-messages/500";
  private final ChatModerationService service = mock(ChatModerationService.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new ChatPinController(service))
        .setControllerAdvice(new ChatRestExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        new AuthUser(1L, Role.PROFESSOR), null, List.of()));
  }

  @AfterEach void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test void pinReturnsTheMessageAndUnpinReturnsNoContent() throws Exception {
    when(service.pin(42L, 1L, 500L)).thenReturn(new ChatMessageResponse(500L, 900L, "익명", false, "질문",
        null, null, Instant.parse("2026-11-19T00:00:00Z")));
    mvc.perform(put(PIN)).andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(500))
        .andExpect(jsonPath("$.nickname").value("익명"))
        .andExpect(jsonPath("$.isOwner").value(false))
        .andExpect(jsonPath("$.createdAt").value("2026-11-19T00:00:00Z"))
        .andExpect(jsonPath("$.length()").value(8));
    mvc.perform(delete(PIN)).andExpect(status().isNoContent());
    verify(service).unpin(42L, 1L, 500L);
  }

  @Test void pinErrorsFollowTheApiSpec() throws Exception {
    when(service.pin(42L, 1L, 500L)).thenThrow(new ChatException(ChatErrorCode.MESSAGE_NOT_FOUND),
        new ChatException(ChatErrorCode.MESSAGE_DELETED), new ChatException(ChatErrorCode.FORBIDDEN));
    mvc.perform(put(PIN)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MESSAGE_NOT_FOUND"));
    mvc.perform(put(PIN)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MESSAGE_DELETED"));
    mvc.perform(put(PIN)).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
  }

  @Test void malformedMessageIdIsAValidationError() throws Exception {
    mvc.perform(put("/api/rooms/42/pinned-messages/invalid")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("messageId"));
    verifyNoInteractions(service);
  }
}
