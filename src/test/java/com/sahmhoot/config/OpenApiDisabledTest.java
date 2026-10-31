package com.sahmhoot.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** local이 아닌 프로파일(운영)에서는 API 문서가 열리지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDisabledTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void local이_아니면_api_docs가_열리지_않는다() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
  }
}
