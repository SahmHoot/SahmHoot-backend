package com.sahmhoot.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@WithMockUser
class GlobalExceptionHandlerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void 없는_경로는_404_NOT_FOUND로_응답한다() throws Exception {
    mockMvc
        .perform(get("/api/does-not-exist"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("요청한 주소를 찾을 수 없습니다."))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }

  @Test
  void 지원하지_않는_메서드는_405_METHOD_NOT_ALLOWED로_응답한다() throws Exception {
    mockMvc
        .perform(delete("/api/health"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string("Allow", "GET"))
        .andExpect(jsonPath("$.status").value(405))
        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
        .andExpect(jsonPath("$.message").value("지원하지 않는 요청 방식입니다."))
        .andExpect(jsonPath("$.fieldErrors").doesNotExist());
  }
}
