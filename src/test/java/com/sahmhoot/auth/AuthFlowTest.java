package com.sahmhoot.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sahmhoot.auth.verification.EmailVerificationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class AuthFlowTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private EmailVerificationStore store;

  private ResultActions postJson(String url, String body) throws Exception {
    return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void 인증_가입_로그인_내정보까지_이어진다() throws Exception {
    String email = "flow@test.ac.kr";
    postJson("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}")
        .andExpect(status().isNoContent());
    String code = store.get(email).code();

    postJson(
            "/api/auth/email-verifications/confirm",
            "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}")
        .andExpect(status().isNoContent());

    postJson(
            "/api/auth/signup",
            "{\"email\":\"" + email + "\",\"code\":\"" + code
                + "\",\"password\":\"abcd1234\",\"name\":\"홍길동\",\"role\":\"STUDENT\"}")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.user.role").value("STUDENT"));

    String body =
        postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"abcd1234\"}")
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = JsonPath.read(body, "$.accessToken");

    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(email))
        .andExpect(jsonPath("$.name").value("홍길동"));
  }

  @Test
  void 토큰_없이_보호_API는_401() throws Exception {
    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void 위조_토큰은_401() throws Exception {
    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer abc.def.ghi"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 인증_코드는_60초에_한_번() throws Exception {
    String body = "{\"email\":\"again@test.ac.kr\"}";
    postJson("/api/auth/email-verifications", body).andExpect(status().isNoContent());
    postJson("/api/auth/email-verifications", body)
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("VERIFICATION_TOO_FREQUENT"));
  }

  @Test
  void 인증_없이_가입하면_EMAIL_NOT_VERIFIED() throws Exception {
    postJson(
            "/api/auth/signup",
            "{\"email\":\"nover@test.ac.kr\",\"code\":\"123456\",\"password\":\"abcd1234\","
                + "\"name\":\"김\",\"role\":\"PROFESSOR\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
  }

  @Test
  void 약한_비밀번호는_VALIDATION_FAILED() throws Exception {
    postJson(
            "/api/auth/signup",
            "{\"email\":\"weak@test.ac.kr\",\"code\":\"123456\",\"password\":\"abcdefgh\","
                + "\"name\":\"김\",\"role\":\"PROFESSOR\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
  }

  @Test
  void 틀린_비밀번호는_INVALID_CREDENTIALS() throws Exception {
    postJson("/api/auth/login", "{\"email\":\"none@test.ac.kr\",\"password\":\"abcd1234\"}")
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
  }
}
