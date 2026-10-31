package com.sahmhoot.questionset;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.questionset.auth.*;
import com.sahmhoot.questionset.controller.QuestionController;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.exception.*;
import com.sahmhoot.questionset.service.QuestionService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QuestionControllerTest {
  private final QuestionSetUserLookup users = mock(QuestionSetUserLookup.class);
  private final QuestionService service = mock(QuestionService.class);
  private MockMvc mvc;
  private static final String PATH = "/api/question-sets/7/questions";
  private static final String VALID = """
      {"type":"TRUE_FALSE","content":"문항",
       "choices":[{"content":"O","isCorrect":true},{"content":"X","isCorrect":false}]}
      """;

  @BeforeEach void setup() {
    var auth = new QuestionSetCurrentUserResolver(users);
    mvc = MockMvcBuilders.standaloneSetup(new QuestionController(auth, service))
        .setControllerAdvice(new QuestionSetRestExceptionHandler())
        .addInterceptors(new QuestionSetAuthenticationInterceptor(auth)).build();
    SecurityContextHolder.getContext().setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated("42", null, List.of()));
    when(users.findById(42L)).thenReturn(Optional.of(new QuestionSetCurrentUser(42L, "PROFESSOR", "교수")));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void crudContracts() throws Exception {
    var response = new QuestionResponse(101L, 1, QuestionType.TRUE_FALSE, "문항", 60,
        List.of(new ChoiceResponse(301L, 1, "O", true), new ChoiceResponse(302L, 2, "X", false)));
    when(service.add(eq(7L), eq(42L), any())).thenReturn(response);
    when(service.update(eq(7L), eq(101L), eq(42L), any())).thenReturn(response);
    var requests = List.of(post(PATH), put(PATH + "/101"));
    for (int i = 0; i < requests.size(); i++) {
      mvc.perform(requests.get(i).contentType(MediaType.APPLICATION_JSON).content(VALID))
          .andExpect(status().is(i == 0 ? 201 : 200))
          .andExpect(jsonPath("$.id").value(101)).andExpect(jsonPath("$.timeLimitSeconds").value(60))
          .andExpect(jsonPath("$.choices[0].isCorrect").value(true));
    }
    mvc.perform(delete(PATH + "/101")).andExpect(status().isNoContent()).andExpect(content().string(""));
    verify(service).delete(7L, 101L, 42L);
  }

  @Test void invalidChoicesAndBodiesReturn400ForBothWrites() throws Exception {
    var invalid = List.of("{}", "{", VALID.replace("TRUE_FALSE", "MULTIPLE_CHOICE"),
        VALID.replace("true", "false"), VALID.replace("false", "true"),
        VALID.replace("\"X\"", "\"O\""), VALID.replace("\"isCorrect\":true", "\"isCorrect\":null"),
        VALID.replace("{\"content\":\"O\",\"isCorrect\":true}", "null"),
        VALID.replace("\"content\":\"문항\"", "\"content\":\" \""),
        VALID.replace("\"choices\":[", "\"timeLimitSeconds\":9,\"choices\":["));
    for (String body : invalid) {
      for (var request : List.of(post(PATH), put(PATH + "/101"))) {
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors").isArray());
      }
    }
    verifyNoInteractions(service);
  }

  @Test void unauthenticatedRequestsPrecedeBodyValidation() throws Exception {
    SecurityContextHolder.clearContext();
    for (var request : List.of(post(PATH), put(PATH + "/101"), delete(PATH + "/101"))) {
      mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{"))
          .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @CsvSource({"MULTIPLE_CHOICE,0,0", "MULTIPLE_CHOICE,3,1", "MULTIPLE_CHOICE,5,1",
      "MULTIPLE_CHOICE,4,0", "MULTIPLE_CHOICE,4,2", "TRUE_FALSE,1,1", "TRUE_FALSE,3,1",
      "TRUE_FALSE,2,0", "TRUE_FALSE,2,2"})
  void invalidChoiceAndAnswerCountsReturn400(String type, int count, int correct) throws Exception {
    var items = new ArrayList<String>();
    for (int i = 0; i < count; i++) {
      String content = type.equals("TRUE_FALSE") ? (i == 0 ? "O" : "X") : "choice " + i;
      items.add("{\"content\":\"" + content + "\",\"isCorrect\":" + (i < correct) + "}");
    }
    String body = "{\"type\":\"" + type + "\",\"content\":\"문항\",\"choices\":["
        + String.join(",", items) + "]}";
    for (var request : List.of(post(PATH), put(PATH + "/101"))) {
      mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors[0].field").value("choices"));
    }
    verifyNoInteractions(service);
  }

  @Test void invalidPathIdsReturnValidationFailed() throws Exception {
    for (String id : List.of("0", "-1", "bad")) {
      mvc.perform(delete(PATH + "/" + id)).andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
      mvc.perform(post("/api/question-sets/" + id + "/questions")
          .contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  @Test void domainErrorsUseSharedFormat() throws Exception {
    for (var code : List.of(QuestionSetErrorCode.FORBIDDEN, QuestionSetErrorCode.QUESTION_NOT_FOUND)) {
      doThrow(new QuestionSetException(code)).when(service).delete(7L, 101L, 42L);
      mvc.perform(delete(PATH + "/101")).andExpect(status().is(code.status))
          .andExpect(jsonPath("$.code").value(code.name()));
    }
  }
}
