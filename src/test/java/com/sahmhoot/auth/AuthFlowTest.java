package com.sahmhoot.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sahmhoot.auth.verification.EmailVerificationService;
import com.sahmhoot.auth.verification.EmailVerificationStore;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.auth.verification.VerificationMailSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  @MockitoBean private VerificationMailSender mailSender;
  @Autowired private UserRepository userRepository;
  @Autowired private PasswordEncoder passwordEncoder;

  private static final String RESET_EMAIL = "reset@test.ac.kr";

  @AfterEach void clearVerificationFixtures() {
    store.remove("flow@test.ac.kr");
    store.remove("again@test.ac.kr");
    store.remove("unsupported@test.ac.kr");
    store.remove(EmailVerificationService.passwordResetKey(RESET_EMAIL));
    store.remove(EmailVerificationService.passwordResetKey("nobody@test.ac.kr"));
  }

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

  @Test void unsupportedContentTypePreserves415AndDoesNotCreateVerification() throws Exception {
    mockMvc.perform(post("/api/auth/email-verifications").contentType(MediaType.TEXT_PLAIN)
            .content("{\"email\":\"unsupported@test.ac.kr\"}"))
        .andExpect(status().isUnsupportedMediaType());
    assertThat(store.get("unsupported@test.ac.kr")).isNull();
  }

  @Test void unsupportedMethodPreserves405AndAllowHeader() throws Exception {
    mockMvc.perform(get("/api/auth/email-verifications"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
  }

  @Test
  void 위조_토큰은_401() throws Exception {
    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer abc.def.ghi"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 인증_코드는_30초에_한_번() throws Exception {
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

  @Test
  void 비밀번호_찾기로_새_비밀번호를_설정하면_새_비밀번호로만_로그인된다() throws Exception {
    userRepository.save(
        User.create(RESET_EMAIL, passwordEncoder.encode("oldpass123"), "재설정", null, Role.STUDENT));
    postJson("/api/auth/password-reset/email-verifications", "{\"email\":\"" + RESET_EMAIL + "\"}")
        .andExpect(status().isNoContent());
    String code = store.get(EmailVerificationService.passwordResetKey(RESET_EMAIL)).code();
    org.mockito.Mockito.verify(mailSender).sendPasswordReset(RESET_EMAIL, code);

    String reset =
        "{\"email\":\"" + RESET_EMAIL + "\",\"code\":\"" + code + "\",\"newPassword\":\"newpass456\"}";
    postJson("/api/auth/password-reset", reset)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

    postJson(
            "/api/auth/password-reset/email-verifications/confirm",
            "{\"email\":\"" + RESET_EMAIL + "\",\"code\":\"" + code + "\"}")
        .andExpect(status().isNoContent());
    postJson("/api/auth/password-reset", reset).andExpect(status().isNoContent());

    postJson("/api/auth/login", "{\"email\":\"" + RESET_EMAIL + "\",\"password\":\"oldpass123\"}")
        .andExpect(status().isUnauthorized());
    postJson("/api/auth/login", "{\"email\":\"" + RESET_EMAIL + "\",\"password\":\"newpass456\"}")
        .andExpect(status().isOk());
  }

  @Test
  void 가입되지_않은_이메일도_비밀번호_찾기_발송은_같은_204() throws Exception {
    postJson("/api/auth/password-reset/email-verifications", "{\"email\":\"nobody@test.ac.kr\"}")
        .andExpect(status().isNoContent());
    org.mockito.Mockito.verifyNoInteractions(mailSender);
    postJson("/api/auth/password-reset/email-verifications", "{\"email\":\"nobody@test.ac.kr\"}")
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("VERIFICATION_TOO_FREQUENT"));
  }

  @Test
  void 비밀번호_찾기_새_비밀번호도_가입과_같은_규칙() throws Exception {
    postJson(
            "/api/auth/password-reset",
            "{\"email\":\"" + RESET_EMAIL + "\",\"code\":\"123456\",\"newPassword\":\"short\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void 바이트_수가_BCrypt_한도를_넘는_비밀번호는_400() throws Exception {
    // 20글자라 글자 수 규칙은 통과하지만 UTF-8로 74바이트다. 인코더까지 가면 500이 된다.
    String tooLong = "a1" + "\uD83D\uDE00".repeat(18);
    assertThat(tooLong.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isEqualTo(74);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> passwordEncoder.encode(tooLong))
        .as("BCrypt가 72바이트 초과를 거부하는지(이 검증이 필요한 이유)")
        .isInstanceOf(IllegalArgumentException.class);
    postJson(
            "/api/auth/password-reset",
            "{\"email\":\"" + RESET_EMAIL + "\",\"code\":\"123456\",\"newPassword\":\"" + tooLong + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    postJson(
            "/api/auth/signup",
            "{\"email\":\"flow@test.ac.kr\",\"code\":\"123456\",\"password\":\"" + tooLong
                + "\",\"name\":\"홍길동\",\"role\":\"STUDENT\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }
}
