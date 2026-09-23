package com.sahmhoot.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
class SecurityErrorResponseTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void 인증이_필요한_경로는_401_에러_형식으로_응답한다() throws Exception {
    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }
}
