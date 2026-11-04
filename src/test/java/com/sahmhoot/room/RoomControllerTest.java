package com.sahmhoot.room;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.room.controller.RoomController;
import com.sahmhoot.room.dto.JoinRoomRequest;
import com.sahmhoot.room.dto.JoinRoomResponse;
import com.sahmhoot.room.dto.RoomByCodeResponse;
import com.sahmhoot.room.dto.RoomSummaryResponse;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.exception.RoomExceptionHandler;
import com.sahmhoot.room.service.RoomManagementService;
import com.sahmhoot.room.service.RoomEntryService;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.room.service.RoomStateService;
import com.sahmhoot.user.Role;
import java.time.Instant;
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
  private final RoomManagementService management = mock(RoomManagementService.class);
  private final RoomEntryService entry = mock(RoomEntryService.class);
  private final RoomStateService states = mock(RoomStateService.class);
  private MockMvc mvc;

  @BeforeEach void setup() {
    mvc = MockMvcBuilders.standaloneSetup(new RoomController(service, management, entry, states))
        .setControllerAdvice(new RoomExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthUser(1L, Role.PROFESSOR), null, List.of()));
  }

  @AfterEach void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test void byCodeUsesAuthenticatedUserAndReturnsSevenContractFields() throws Exception {
    when(entry.byCode(1L, "123456")).thenReturn(new RoomByCodeResponse(
        42L, "자료구조", "담당교수", RoomStatus.OPEN, false, false, null));

    mvc.perform(get("/api/rooms/by-code/123456"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(jsonPath("$.roomId").value(42))
        .andExpect(jsonPath("$.title").value("자료구조"))
        .andExpect(jsonPath("$.ownerName").value("담당교수"))
        .andExpect(jsonPath("$.status").value("OPEN"))
        .andExpect(jsonPath("$.joined").value(false))
        .andExpect(jsonPath("$.isOwner").value(false))
        .andExpect(jsonPath("$.myNickname").value(nullValue()));
    verify(entry).byCode(1L, "123456");
    verifyNoInteractions(service, management);
  }

  @ParameterizedTest @ValueSource(booleans = {true, false})
  void joinReturns201ForFirstEntryAnd200ForReentry(boolean created) throws Exception {
    var request = new JoinRoomRequest("학생닉네임");
    when(entry.join(1L, "123456", request)).thenReturn(new RoomEntryService.EntryResult(
        new JoinRoomResponse(42L, 900L, "학생닉네임"), created));

    mvc.perform(post("/api/rooms/by-code/123456/participants")
        .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"  학생닉네임  \"}"))
        .andExpect(status().is(created ? 201 : 200))
        .andExpect(jsonPath("$.length()").value(3))
        .andExpect(jsonPath("$.roomId").value(42))
        .andExpect(jsonPath("$.participantId").value(900))
        .andExpect(jsonPath("$.nickname").value("학생닉네임"));
    verify(entry).join(1L, "123456", request);
    verifyNoInteractions(service, management);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"nickname\":null}", "{\"nickname\":\"\"}",
      "{\"nickname\":\"   \"}", "{\"nickname\":\" 가 \"}", "{\"nickname\":\"가나다라마바사아자차카\"}"})
  void invalidNicknameIsRejectedBeforeCallingEntryService(String body) throws Exception {
    mvc.perform(post("/api/rooms/by-code/123456/participants")
        .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"));
    verifyNoInteractions(entry, service, management);
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

  @Test void mineUsesAuthenticatedUserAndReturnsRoomSummaries() throws Exception {
    when(management.mine(1L)).thenReturn(List.of(new RoomSummaryResponse(
        42L, "수업", RoomStatus.OPEN, "123456", 0, Instant.parse("2026-11-01T00:00:00Z"))));
    mvc.perform(get("/api/rooms/mine"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(42))
        .andExpect(jsonPath("$[0].onlineCount").value(0));
    verify(management).mine(1L);
  }

  @Test void closeUsesAuthenticatedUserAndReturnsNoContent() throws Exception {
    mvc.perform(patch("/api/rooms/42/close"))
        .andExpect(status().isNoContent()).andExpect(content().string(""));
    verify(management).close(42L, 1L);
  }

  @Test void malformedRoomIdKeepsValidationField() throws Exception {
    mvc.perform(patch("/api/rooms/abc/close"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("roomId"));
    verifyNoInteractions(management);
  }

  @Test void stateDelegatesRoomIdAndAuthenticatedUser() throws Exception {
    mvc.perform(get("/api/rooms/42/state")).andExpect(status().isOk());
    verify(states).state(42L, 1L);
    verifyNoInteractions(service, management, entry);
  }

  @Test void malformedStateRoomIdIsRejectedBeforeCallingService() throws Exception {
    mvc.perform(get("/api/rooms/abc/state"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("roomId"));
    verifyNoInteractions(states);
  }
}
