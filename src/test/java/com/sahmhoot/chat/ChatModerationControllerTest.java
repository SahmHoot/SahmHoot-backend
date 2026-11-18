package com.sahmhoot.chat;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.chat.controller.ChatModerationController;
import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.dto.ChatRestrictionResponse;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.exception.ChatRestExceptionHandler;
import com.sahmhoot.chat.service.ChatModerationService;
import com.sahmhoot.user.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ChatModerationControllerTest {
  private static final String ROOM = "/api/rooms/42";
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
    when(service.author(42L, 1L, 500L)).thenReturn(new ChatAuthorResponse(200L, "익명학생", "student@example.test"));
    mvc.perform(get(ROOM + "/messages/500/author")).andExpect(status().isOk())
        .andExpect(jsonPath("$.participantId").value(200))
        .andExpect(jsonPath("$.nickname").value("익명학생"))
        .andExpect(jsonPath("$.email").value("student@example.test"))
        .andExpect(jsonPath("$.length()").value(3));
    mvc.perform(delete(ROOM + "/messages/500")).andExpect(status().isNoContent());
    verify(service).delete(42L, 1L, 500L);
  }

  @Test void forbiddenResponseOmitsAuthorAndUsesTheExistingErrorEnvelope() throws Exception {
    when(service.author(42L, 1L, 500L)).thenThrow(new ChatException(ChatErrorCode.FORBIDDEN));
    mvc.perform(get(ROOM + "/messages/500/author")).andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403))
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.email").doesNotExist());
  }

  @Test void ownerRestrictionReturnsItsOwnErrorCode() throws Exception {
    when(service.updateRestriction(42L, 1L, 100L, true))
        .thenThrow(new ChatException(ChatErrorCode.CANNOT_BLOCK_OWNER));
    mvc.perform(update(100L, "{\"chatBlocked\":true}")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_OWNER"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void restrictionResponseIncludesParticipantAndRequestedState(boolean blocked) throws Exception {
    when(service.updateRestriction(42L, 1L, 200L, blocked))
        .thenReturn(new ChatRestrictionResponse(200L, "익명학생", blocked));
    mvc.perform(update(200L, "{\"chatBlocked\":" + blocked + "}")).andExpect(status().isOk())
        .andExpect(jsonPath("$.participantId").value(200))
        .andExpect(jsonPath("$.nickname").value("익명학생"))
        .andExpect(jsonPath("$.chatBlocked").value(blocked))
        .andExpect(jsonPath("$.length()").value(3));
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"chatBlocked\":null}"})
  void missingOrNullBlockedValueReturnsFieldError(String body) throws Exception {
    mvc.perform(update(200L, body)).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("입력값이 올바르지 않습니다."))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("chatBlocked"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value("필수 값입니다."));
    verifyNoInteractions(service);
  }

  @Test void brokenJsonReturnsValidationError() throws Exception {
    mvc.perform(update(200L, "{\"chatBlocked\":")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("body"));
    verifyNoInteractions(service);
  }

  @Test void legacyManagementRoutesAreUnmapped() throws Exception {
    mvc.perform(put(ROOM + "/chat/restrictions/200")).andExpect(status().isNotFound());
    mvc.perform(delete(ROOM + "/chat/restrictions/200")).andExpect(status().isNotFound());
    mvc.perform(delete(ROOM + "/chat/messages/500")).andExpect(status().isNotFound());
    mvc.perform(get(ROOM + "/chat/messages/500/author")).andExpect(status().isNotFound());
    verifyNoInteractions(service);
  }

  private MockHttpServletRequestBuilder update(long participantId, String body) {
    return patch(ROOM + "/participants/" + participantId).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  @Test void malformedIdUsesValidationErrorAndUnsupportedMethodStillReturns405() throws Exception {
    mvc.perform(get(ROOM + "/messages/invalid/author")).andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("messageId"));
    mvc.perform(post(ROOM + "/messages/500")).andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(service);
  }
}
