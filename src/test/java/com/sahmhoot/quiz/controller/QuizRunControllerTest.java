package com.sahmhoot.quiz.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.exception.QuizRestExceptionHandler;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.service.QuizCoreService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class QuizRunControllerTest {

  private MockMvc mockMvc;

  @Mock
  private QuizCoreService quizCoreService;

  @InjectMocks
  private QuizRunController quizRunController;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(quizRunController)
        .setControllerAdvice(new QuizRestExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("23. 퀴즈 시작 API 성공 시 201 Created와 시작 정보를 반환한다")
  void startQuiz_success() throws Exception {
    Long roomId = 42L;
    StartQuizResponse response = new StartQuizResponse(
        77L,
        5,
        810L,
        Instant.parse("2026-10-14T10:32:15Z")
    );

    given(quizCoreService.startQuiz(eq(roomId), any(StartQuizRequest.class), any(Long.class)))
        .willReturn(response);

    String requestJson = "{\"questionSetId\":7}";

    mockMvc.perform(post("/api/rooms/{roomId}/quiz-runs", roomId)
            .principal(() -> "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.runId").value(77L))
        .andExpect(jsonPath("$.totalQuestions").value(5))
        .andExpect(jsonPath("$.firstRunQuestionId").value(810L))
        .andExpect(jsonPath("$.closesAt").value("2026-10-14T10:32:15Z"));
  }

  @Test
  @DisplayName("23. 퀴즈 시작 시 questionSetId가 누락되면 400 VALIDATION_FAILED를 반환한다")
  void startQuiz_validationFailed() throws Exception {
    Long roomId = 42L;
    String emptyJson = "{}";

    mockMvc.perform(post("/api/rooms/{roomId}/quiz-runs", roomId)
            .principal(() -> "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(emptyJson))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("questionSetId"));
  }

  @Test
  @DisplayName("23. 이미 진행 중인 퀴즈가 있으면 409 QUIZ_ALREADY_RUNNING을 반환한다")
  void startQuiz_alreadyRunning() throws Exception {
    Long roomId = 42L;
    String requestJson = "{\"questionSetId\":7}";

    given(quizCoreService.startQuiz(eq(roomId), any(StartQuizRequest.class), any(Long.class)))
        .willThrow(new BusinessException(ErrorCode.QUIZ_ALREADY_RUNNING));

    mockMvc.perform(post("/api/rooms/{roomId}/quiz-runs", roomId)
            .principal(() -> "1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("QUIZ_ALREADY_RUNNING"));
  }

  @Test
  @DisplayName("23. 인증 정보(Principal)가 없으면 401 UNAUTHORIZED를 반환한다")
  void startQuiz_withoutPrincipal_returns401Unauthorized() throws Exception {
    Long roomId = 42L;
    String requestJson = "{\"questionSetId\":7}";

    mockMvc.perform(post("/api/rooms/{roomId}/quiz-runs", roomId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("23. 숫자가 아닌 유효하지 않은 Principal이면 401 UNAUTHORIZED를 반환한다")
  void startQuiz_invalidPrincipal_returns401Unauthorized() throws Exception {
    Long roomId = 42L;
    String requestJson = "{\"questionSetId\":7}";

    mockMvc.perform(post("/api/rooms/{roomId}/quiz-runs", roomId)
            .principal(() -> "anonymousUser")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("24. 퀴즈 중단 API 성공 시 204 No Content를 반환한다")
  void abortQuiz_success() throws Exception {
    Long roomId = 42L;
    Long runId = 77L;

    willDoNothing().given(quizCoreService).abortQuiz(eq(roomId), eq(runId), any(Long.class));

    mockMvc.perform(delete("/api/rooms/{roomId}/quiz-runs/{runId}", roomId, runId)
            .principal(() -> "1"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("24. 진행 중이 아닌 퀴즈를 중단 시도하면 409 QUIZ_NOT_RUNNING을 반환한다")
  void abortQuiz_notRunning() throws Exception {
    Long roomId = 42L;
    Long runId = 77L;

    willThrow(new BusinessException(ErrorCode.QUIZ_NOT_RUNNING))
        .given(quizCoreService).abortQuiz(eq(roomId), eq(runId), any(Long.class));

    mockMvc.perform(delete("/api/rooms/{roomId}/quiz-runs/{runId}", roomId, runId)
            .principal(() -> "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("QUIZ_NOT_RUNNING"));
  }

  @Test
  @DisplayName("24. 인증 정보 없이 퀴즈 중단 시 401 UNAUTHORIZED를 반환한다")
  void abortQuiz_withoutPrincipal_returns401Unauthorized() throws Exception {
    Long roomId = 42L;
    Long runId = 77L;

    mockMvc.perform(delete("/api/rooms/{roomId}/quiz-runs/{runId}", roomId, runId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("25. 결과 닫기 API 성공 시 204 No Content를 반환한다")
  void closeResult_success() throws Exception {
    Long roomId = 42L;

    willDoNothing().given(quizCoreService).closeResult(eq(roomId), any(Long.class));

    mockMvc.perform(post("/api/rooms/{roomId}/wait", roomId)
            .principal(() -> "1"))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("25. 결과 화면 상태가 아닌 방에서 결과 닫기 시도 시 409 ROOM_NOT_SHOWING_RESULT를 반환한다")
  void closeResult_notShowingResult() throws Exception {
    Long roomId = 42L;

    willThrow(new BusinessException(ErrorCode.ROOM_NOT_SHOWING_RESULT))
        .given(quizCoreService).closeResult(eq(roomId), any(Long.class));

    mockMvc.perform(post("/api/rooms/{roomId}/wait", roomId)
            .principal(() -> "1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("ROOM_NOT_SHOWING_RESULT"));
  }

  @Test
  @DisplayName("25. 인증 정보 없이 결과 닫기 시 401 UNAUTHORIZED를 반환한다")
  void closeResult_withoutPrincipal_returns401Unauthorized() throws Exception {
    Long roomId = 42L;

    mockMvc.perform(post("/api/rooms/{roomId}/wait", roomId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  @DisplayName("23. 인증된 Principal이 제공되면 hostUserId로 해당 사용자 ID를 전달한다 (Issue 3)")
  void startQuiz_withAuthenticatedPrincipal_extractsHostUserId() throws Exception {
    Long roomId = 42L;
    Long authenticatedUserId = 123L;
    StartQuizResponse response = new StartQuizResponse(
        77L,
        5,
        810L,
        Instant.parse("2026-10-14T10:32:15Z")
    );

    given(quizCoreService.startQuiz(eq(roomId), any(StartQuizRequest.class), eq(authenticatedUserId)))
        .willReturn(response);

    String requestJson = "{\"questionSetId\":7}";

    mockMvc.perform(post("/api/rooms/{roomId}/quiz-runs", roomId)
            .principal(() -> String.valueOf(authenticatedUserId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.runId").value(77L));
  }
}
