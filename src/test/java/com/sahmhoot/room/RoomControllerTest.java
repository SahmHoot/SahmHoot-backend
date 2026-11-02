package com.sahmhoot.room;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.room.controller.RoomController;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.exception.RoomExceptionHandler;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.user.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RoomControllerTest {
  private final RoomService service = mock(RoomService.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new RoomController(service))
        .setControllerAdvice(new RoomExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthUser(1L, Role.PROFESSOR), null, List.of()));
  }

  @AfterEach void clear() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest @ValueSource(strings = {"text/plain", "application/xml"})
  void unsupportedContentTypeKeeps415(String contentType) throws Exception {
    mvc.perform(post("/api/rooms").contentType(MediaType.parseMediaType(contentType))
        .content("hello")).andExpect(status().isUnsupportedMediaType());
    verifyNoInteractions(service);
  }

  @Test void unsupportedHttpMethodKeeps405() throws Exception {
    mvc.perform(put("/api/rooms").contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"수업\"}")).andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(service);
  }

  @ParameterizedTest @EnumSource(RoomErrorCode.class)
  void roomErrorsKeepExistingResponseContract(RoomErrorCode code) throws Exception {
    when(service.create(eq(1L), any())).thenThrow(new RoomException(code));
    mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"수업\"}"))
        .andExpect(status().is(code.status().value()))
        .andExpect(jsonPath("$.status").value(code.status().value()))
        .andExpect(jsonPath("$.code").value(code.name()))
        .andExpect(jsonPath("$.message").value(code.defaultMessage()))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }

  @Test void unexpectedFailureStillReturnsInternalError() throws Exception {
    when(service.create(eq(1L), any())).thenThrow(new IllegalStateException("save failed"));
    mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"수업\"}"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));
  }

  @Test void validationErrorsKeepFieldDetails() throws Exception {
    mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("title"));
    verifyNoInteractions(service);
  }

  @Test void malformedBodyKeepsExistingResponseContract() throws Exception {
    mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다."))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("body"));
    verifyNoInteractions(service);
  }
}
