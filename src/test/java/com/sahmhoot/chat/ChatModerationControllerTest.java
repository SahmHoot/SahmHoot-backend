package com.sahmhoot.chat;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.chat.controller.ChatModerationController;
import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.exception.ChatRestExceptionHandler;
import com.sahmhoot.chat.service.ChatModerationService;
import com.sahmhoot.user.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatModerationControllerTest {
  private static final String CHAT = "/api/rooms/42/chat";
  private final ChatModerationService service = mock(ChatModerationService.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new ChatModerationController(service))
        .setControllerAdvice(new ChatRestExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
        new AuthUser(1L, Role.PROFESSOR), null, List.of()));
  }

  @AfterEach void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test void managementRoutesUseTheAuthenticatedUserAndPathIds() throws Exception {
    when(service.author(42L, 1L, 500L)).thenReturn(new ChatAuthorResponse("student@example.test"));
    mvc.perform(get(CHAT + "/messages/500/author")).andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("student@example.test"))
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(put(CHAT + "/restrictions/200")).andExpect(status().isNoContent());
    mvc.perform(delete(CHAT + "/restrictions/200")).andExpect(status().isNoContent());
    mvc.perform(delete(CHAT + "/messages/500")).andExpect(status().isNoContent());
    verify(service).restrict(42L, 1L, 200L);
    verify(service).release(42L, 1L, 200L);
    verify(service).delete(42L, 1L, 500L);
  }

  @Test void forbiddenResponseOmitsAuthorAndUsesTheExistingErrorEnvelope() throws Exception {
    when(service.author(42L, 1L, 500L)).thenThrow(new ChatException(ChatErrorCode.FORBIDDEN));
    mvc.perform(get(CHAT + "/messages/500/author")).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.email").doesNotExist());
  }

  @Test void ownerRestrictionReturnsItsOwnErrorCode() throws Exception {
    doThrow(new ChatException(ChatErrorCode.CANNOT_BLOCK_OWNER)).when(service).restrict(42L, 1L, 100L);
    mvc.perform(put(CHAT + "/restrictions/100")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_OWNER"));
  }

  @Test void malformedIdUsesValidationErrorAndUnsupportedMethodStillReturns405() throws Exception {
    mvc.perform(get(CHAT + "/messages/invalid/author")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("messageId"));
    mvc.perform(post(CHAT + "/messages/500")).andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(service);
  }
}
