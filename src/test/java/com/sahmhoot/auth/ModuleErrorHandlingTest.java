package com.sahmhoot.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 실제 JWT로 다른 모듈(퀴즈) API를 불렀을 때 인증 모듈 핸들러가 끼어들지 않는지 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class ModuleErrorHandlingTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JwtProvider jwtProvider;

  private ResultActions startQuiz(String body) throws Exception {
    String token = jwtProvider.issue(1L, Role.PROFESSOR).accessToken();
    return mockMvc.perform(
        post("/api/rooms/999999/quiz-runs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  @Test
  void 퀴즈_에러는_퀴즈_핸들러가_원래_코드로_응답한다() throws Exception {
    // JWT의 userId가 퀴즈 컨트롤러까지 전달되어야 401이 아니라 ROOM_NOT_FOUND가 나온다.
    startQuiz("{\"questionSetId\":1}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
  }

  @Test
  void 퀴즈_입력값_오류도_퀴즈_핸들러가_처리한다() throws Exception {
    // 메시지로 어느 핸들러가 응답했는지 구분한다(퀴즈: "입력값 형식 오류입니다.").
    startQuiz("{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value("입력값 형식 오류입니다."));
  }
}
