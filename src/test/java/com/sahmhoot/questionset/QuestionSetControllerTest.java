package com.sahmhoot.questionset;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.questionset.auth.*;
import com.sahmhoot.questionset.controller.QuestionSetController;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.exception.*;
import com.sahmhoot.questionset.service.QuestionSetService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QuestionSetControllerTest {
  private final QuestionSetUserLookup users = mock(QuestionSetUserLookup.class);
  private final QuestionSetService sets = mock(QuestionSetService.class);
  private MockMvc mvc;
  private final QuestionSetSummaryResponse summary = new QuestionSetSummaryResponse(
      7L, "세트", Visibility.PRIVATE, 1, false, Instant.parse("2026-10-14T10:00:00Z"));
  private static final String SUMMARY = """
      {"id":7,"title":"세트","visibility":"PRIVATE","questionCount":1,
       "used":false,"updatedAt":"2026-10-14T10:00:00Z"}
      """;

  @BeforeEach void setup() {
    var auth = new QuestionSetCurrentUserResolver(users);
    mvc = MockMvcBuilders.standaloneSetup(new QuestionSetController(auth, sets))
        .setControllerAdvice(new QuestionSetRestExceptionHandler())
        .addInterceptors(new QuestionSetAuthenticationInterceptor(auth)).build();
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("42", null, List.of()));
    when(users.findById(42L)).thenReturn(Optional.of(new QuestionSetCurrentUser(42L, "PROFESSOR", "교수")));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void allFiveEndpointContractsMatchLocalV15Fields() throws Exception {
    when(sets.mine(any())).thenReturn(List.of(summary));
    when(sets.create(any(), any())).thenReturn(summary);
    when(sets.update(eq(7L), eq(42L), any())).thenReturn(summary);
    var q = new QuestionResponse(101L, 1, QuestionType.TRUE_FALSE, "문항", 60,
        List.of(new ChoiceResponse(301L, 1, "O", true), new ChoiceResponse(302L, 2, "X", false)));
    when(sets.detail(7L, 42L)).thenReturn(new QuestionSetDetailResponse(7L, "세트",
        Visibility.PRIVATE, "설명", "교수", List.of(q)));
    mvc.perform(get("/api/question-sets/mine")).andExpect(status().isOk())
        .andExpect(content().json("[" + SUMMARY + "]", org.springframework.test.json.JsonCompareMode.STRICT));
    mvc.perform(post("/api/question-sets").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"세트\"}"))
        .andExpect(status().isCreated())
        .andExpect(content().json(SUMMARY, org.springframework.test.json.JsonCompareMode.STRICT));
    mvc.perform(get("/api/question-sets/7")).andExpect(status().isOk())
        .andExpect(content().json("""
            {"id":7,"title":"세트","visibility":"PRIVATE","description":"설명","ownerName":"교수",
             "questions":[{"id":101,"orderNo":1,"type":"TRUE_FALSE","content":"문항","timeLimitSeconds":60,
               "choices":[{"id":301,"orderNo":1,"content":"O","isCorrect":true},
                          {"id":302,"orderNo":2,"content":"X","isCorrect":false}]}]}
            """, org.springframework.test.json.JsonCompareMode.STRICT));
    mvc.perform(patch("/api/question-sets/7").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"세트\"}"))
        .andExpect(status().isOk())
        .andExpect(content().json(SUMMARY, org.springframework.test.json.JsonCompareMode.STRICT));
    mvc.perform(delete("/api/question-sets/7")).andExpect(status().isNoContent()).andExpect(content().string(""));
  }

  @Test void allEndpointsRejectMissingAuthentication() throws Exception {
    SecurityContextHolder.clearContext();
    var requests = List.of(get("/api/question-sets/mine"), get("/api/question-sets/7"),
        post("/api/question-sets").content("{\"title\":\"세트\"}"),
        patch("/api/question-sets/7").content("{}"), delete("/api/question-sets/7"));
    for (var request : requests) {
      mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
          .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }
    verifyNoInteractions(sets);
  }

  @Test void missingAuthenticationPrecedesMalformedInput() throws Exception {
    SecurityContextHolder.clearContext();
    mvc.perform(post("/api/question-sets").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test void malformedAndValidationErrorsUseSpecifiedFormat() throws Exception {
    for (String body : List.of("{}", "{", "{\"title\":null}", "{\"title\":\" \"}",
        "{\"title\":\"" + "a".repeat(51) + "\"}", "{\"title\":\"세트\",\"visibility\":\"BAD\"}")) {
      mvc.perform(post("/api/question-sets").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors").isArray());
    }
    for (String id : List.of("0", "-1", "not-a-number")) {
      mvc.perform(get("/api/question-sets/" + id)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    verifyNoInteractions(sets);
  }

  @Test void domainErrorStatuses() throws Exception {
    for (var code : List.of(QuestionSetErrorCode.FORBIDDEN, QuestionSetErrorCode.QUESTION_SET_NOT_FOUND,
        QuestionSetErrorCode.QUESTION_SET_IN_USE)) {
      doThrow(new QuestionSetException(code)).when(sets).delete(7L, 42L);
      mvc.perform(delete("/api/question-sets/7")).andExpect(status().is(code.status))
          .andExpect(jsonPath("$.status").value(code.status)).andExpect(jsonPath("$.code").value(code.name()));
    }
  }

  @Test void patchCannotNullRequiredFieldsOrForgePresenceFlags() throws Exception {
    for (String field : List.of("title", "visibility")) {
      mvc.perform(patch("/api/question-sets/7").contentType(MediaType.APPLICATION_JSON)
          .content("{\"" + field + "\":null}")).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value(field));
    }
    when(sets.update(eq(7L), eq(42L), any())).thenReturn(summary);
    mvc.perform(patch("/api/question-sets/7").contentType(MediaType.APPLICATION_JSON)
        .content("{\"titlePresent\":true,\"descriptionPresent\":true,\"visibilityPresent\":true}"))
        .andExpect(status().isOk());
    var captured = org.mockito.ArgumentCaptor.forClass(UpdateQuestionSetRequest.class);
    verify(sets).update(eq(7L), eq(42L), captured.capture());
    org.assertj.core.api.Assertions.assertThat(captured.getValue().isTitlePresent()).isFalse();
    org.assertj.core.api.Assertions.assertThat(captured.getValue().isDescriptionPresent()).isFalse();
    org.assertj.core.api.Assertions.assertThat(captured.getValue().isVisibilityPresent()).isFalse();
  }
}
