package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sahmhoot.SahmhootApplication;
import com.sahmhoot.user.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class VerificationSmtpIntegrationTest {
  private static final LocalSmtpServer SMTP = new LocalSmtpServer();
  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;
  @Autowired UserRepository users;
  @Autowired EmailVerificationStore store;
  private String email;

  @DynamicPropertySource
  static void mailSettings(DynamicPropertyRegistry properties) {
    properties.add("spring.mail.host", () -> "127.0.0.1");
    properties.add("spring.mail.port", SMTP::port);
    properties.add("spring.mail.username", () -> "");
    properties.add("spring.mail.password", () -> "");
    properties.add("app.mail.from", () -> "sender@test.invalid");
    properties.add("app.auth.allowed-email-domains", () -> "test.invalid");
  }

  @BeforeEach void setup() { email = UUID.randomUUID() + "@test.invalid"; }
  @AfterEach void cleanup() {
    store.remove(email);
    users.findByEmail(email).ifPresent(users::delete);
  }
  @AfterAll static void closeReceiver() throws Exception { SMTP.close(); }

  @Test void smtpCodeSupportsSignupLoginAndTokenSurvivesApplicationRestart() throws Exception {
    postJson("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
    var mail = SMTP.take();
    assertThat(mail.getFrom()[0].toString()).isEqualTo("sender@test.invalid");
    assertThat(mail.getAllRecipients()[0].toString()).isEqualTo(email);
    assertThat(mail.getSubject()).isEqualTo("[삼훗] 이메일 인증 코드");
    var matcher = Pattern.compile("인증 코드: (\\d{6})").matcher((String) mail.getContent());
    assertThat(matcher.find()).isTrue();
    String code = matcher.group(1);
    postJson("/api/auth/email-verifications/confirm", "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}")
        .andExpect(status().isNoContent());
    postJson("/api/auth/signup", "{\"email\":\"" + email + "\",\"code\":\"" + code
        + "\",\"password\":\"Test1234\",\"name\":\"메일테스트\",\"role\":\"STUDENT\"}")
        .andExpect(status().isCreated());
    var login = postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"Test1234\"}")
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    String token = json.readTree(login).path("accessToken").asString();
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));

    // 별도 운영 프로파일 컨텍스트가 같은 DB·고정 JWT 설정으로 토큰을 다시 검증한다.
    try (var restarted = new SpringApplicationBuilder(SahmhootApplication.class).run(
        "--spring.profiles.active=test,prod", "--server.port=0", "--spring.main.banner-mode=off",
        "--app.mail.from=sender@test.invalid", "--app.seed.enabled=false")) {
      int port = ((WebServerApplicationContext) restarted).getWebServer().getPort();
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/users/me"))
          .header("Authorization", "Bearer " + token).GET().build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(json.readTree(response.body()).path("email").asString()).isEqualTo(email);
    }
  }

  @Test void smtpFailureReturns503AndCanBeRetriedWithoutLoggingCode() throws Exception {
    SMTP.rejectNextMessage();
    postJson("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}")
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MAIL_SEND_FAILED"));
    assertThat(store.get(email)).isNull();
    postJson("/api/auth/email-verifications", "{\"email\":\"" + email + "\"}").andExpect(status().isNoContent());
    assertThat(SMTP.take().getAllRecipients()[0].toString()).isEqualTo(email);
  }

  private ResultActions postJson(String path, String body) throws Exception {
    return mvc.perform(post(path)
        .contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
